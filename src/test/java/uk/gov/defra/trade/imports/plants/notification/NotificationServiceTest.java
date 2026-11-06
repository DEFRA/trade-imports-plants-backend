package uk.gov.defra.trade.imports.plants.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.defra.trade.imports.plants.utils.LogCapture.logEventsDuring;
import static uk.gov.defra.trade.imports.plants.utils.LogCapture.messagesOf;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import uk.gov.defra.trade.imports.plants.audit.Action;
import uk.gov.defra.trade.imports.plants.audit.Audit;
import uk.gov.defra.trade.imports.plants.audit.AuditRepository;
import uk.gov.defra.trade.imports.plants.audit.Result;
import uk.gov.defra.trade.imports.plants.configuration.NotificationTtlConfig;
import uk.gov.defra.trade.imports.plants.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.plants.exceptions.NotFoundException;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final String REFERENCE = "26-ABC123";
    private static final Integer NO_TTL_DAYS = null;
    private static final AuditContext AUDIT_CONTEXT = new AuditContext("trace-1", "user-1");

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private AuditRepository auditRepository;

    private final List<String> attemptedReferences = new ArrayList<>();

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = newService(ttlConfig(NO_TTL_DAYS, "local"));
    }

    private NotificationService newService(NotificationTtlConfig ttlConfig) {
        return newService(ttlConfig, Clock.systemUTC());
    }

    private NotificationService newService(NotificationTtlConfig ttlConfig, Clock clock) {
        return new NotificationService(
            notificationRepository,
            auditRepository,
            new NotificationCopyMapper(),
            Mappers.getMapper(NotificationContentMapper.class),
            new ReferenceNumberGenerator(),
            ttlConfig,
            clock,
            25,
            50);
    }

    private static NotificationTtlConfig ttlConfig(Integer days, String environment) {
        return new NotificationTtlConfig(days, environment);
    }

    private static NotificationAggregate stored(NotificationStatus status) {
        return NotificationAggregate.builder()
            .id("id-1")
            .referenceNumber(REFERENCE)
            .concurrencyToken(2L)
            .status(status)
            .created(Instant.now().minus(1, ChronoUnit.DAYS))
            .notification(new Notification())
            .build();
    }

    private void repositoryEchoesSaves() {
        when(notificationRepository.save(any(NotificationAggregate.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void saveNotification_shouldCreateDraftWithGeneratedReference_whenNoReferenceSupplied() {
        // Given
        repositoryEchoesSaves();

        // When
        NotificationAggregate result = notificationService.saveNotification(
            NotificationDto.builder().fulfilments(List.of(new Document("k", "v"))).build());

        // Then
        assertThat(result.getReferenceNumber()).matches(ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(result.getCreated()).isNotNull();
        assertThat(result.getFulfilments()).containsExactly(new Document("k", "v"));
    }

    @Test
    void saveNotification_shouldNotStampExpireAt_whenNoTtlConfigured() {
        notificationService = newService(ttlConfig(NO_TTL_DAYS, "dev"));
        repositoryEchoesSaves();

        // When
        NotificationAggregate result =
            notificationService.saveNotification(NotificationDto.builder().build());

        // Then
        assertThat(result.getExpireAt()).isNull();
    }

    @Test
    void saveNotification_shouldNotStampExpireAt_whenEnvironmentIsProd() {
        notificationService = newService(ttlConfig(7, "prod"));
        repositoryEchoesSaves();

        // When
        NotificationAggregate result =
            notificationService.saveNotification(NotificationDto.builder().build());

        // Then
        assertThat(result.getExpireAt()).isNull();
    }

    @Test
    void saveNotification_shouldStampExpireAtRelativeToCreated_whenNonProdAndTtlConfigured() {
        // Given
        notificationService = newService(ttlConfig(7, "dev"));
        repositoryEchoesSaves();

        // When
        NotificationAggregate result =
            notificationService.saveNotification(NotificationDto.builder().build());

        // Then
        assertThat(result.getExpireAt()).isEqualTo(result.getCreated().plus(7, ChronoUnit.DAYS));
    }

    @Test
    void saveNotification_shouldStampExpireAtAnExactNumberOfElapsedDays_whenAClockChangeFallsInTheWindow() {
        ZoneId zoneWithAClockChangeOn28March = ZoneId.of("Europe/London");
        Clock threeDaysBeforeTheClockChange =
            Clock.fixed(Instant.parse("2027-03-25T12:00:00Z"), zoneWithAClockChangeOn28March);
        TimeZone originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(zoneWithAClockChangeOn28March));
        try {
            notificationService = newService(ttlConfig(7, "dev"), threeDaysBeforeTheClockChange);
            repositoryEchoesSaves();

            NotificationAggregate result =
                notificationService.saveNotification(NotificationDto.builder().build());

            assertThat(result.getExpireAt()).isEqualTo(Instant.parse("2027-04-01T12:00:00Z"));
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    private Throwable saveWhoseThreeAttemptsCollide(DuplicateKeyException lastCollision) {
        when(notificationRepository.save(any(NotificationAggregate.class)))
            .thenAnswer(attempt -> recordReferenceThenThrow(attempt, new DuplicateKeyException("first collision")))
            .thenAnswer(attempt -> recordReferenceThenThrow(attempt, new DuplicateKeyException("second collision")))
            .thenAnswer(attempt -> recordReferenceThenThrow(attempt, lastCollision));
        NotificationDto dto = NotificationDto.builder().build();

        return catchThrowable(() -> notificationService.saveNotification(dto));
    }

    private NotificationAggregate recordReferenceThenThrow(InvocationOnMock attempt, DuplicateKeyException collision) {
        NotificationAggregate attempted = attempt.getArgument(0);
        attemptedReferences.add(attempted.getReferenceNumber());
        throw collision;
    }

    @Test
    void saveNotification_shouldGenerateAFreshReferenceForEachAttempt_whenAllPersistenceAttemptsCollide() {
        saveWhoseThreeAttemptsCollide(new DuplicateKeyException("last collision"));

        assertThat(attemptedReferences)
            .hasSize(3)
            .doesNotHaveDuplicates()
            .allMatch(reference -> reference.matches(ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN));
    }

    @Test
    void saveNotification_shouldLogEachAttemptWithNoPromiseOfARetry_whenAllPersistenceAttemptsCollide() {
        // When
        List<ILoggingEvent> events = logEventsDuring(NotificationService.class,
            () -> saveWhoseThreeAttemptsCollide(new DuplicateKeyException("last collision")));

        // Then
        assertThat(messagesOf(events)).containsExactly(
            "Reference number " + attemptedReferences.get(0) + " collided on persistence attempt 1/3",
            "Reference number " + attemptedReferences.get(1) + " collided on persistence attempt 2/3",
            "Reference number " + attemptedReferences.get(2) + " collided on persistence attempt 3/3");
    }

    @Test
    void saveNotification_shouldLogTheThrowableOfEachCollision_whenAllPersistenceAttemptsCollide() {
        // When
        List<ILoggingEvent> events = logEventsDuring(NotificationService.class,
            () -> saveWhoseThreeAttemptsCollide(new DuplicateKeyException("last collision")));

        // Then
        assertThat(events)
            .extracting(event -> event.getThrowableProxy().getMessage())
            .containsExactly("first collision", "second collision", "last collision");
    }

    @Test
    void saveNotification_shouldKeepTheLastCollisionAsTheCause_whenAllPersistenceAttemptsCollide() {
        // Given
        DuplicateKeyException lastCollision = new DuplicateKeyException("last collision");

        // When
        Throwable thrown = saveWhoseThreeAttemptsCollide(lastCollision);

        // Then
        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasCauseReference(lastCollision);
    }

    @Test
    void saveNotification_shouldUpdateExistingDraft_whenReferenceSupplied() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.DRAFT)));
        repositoryEchoesSaves();

        // When
        NotificationAggregate result = notificationService.saveNotification(NotificationDto.builder()
            .referenceNumber(REFERENCE)
            .concurrencyToken(2L)
            .fulfilments(List.of(new Document("k", "updated")))
            .build());

        // Then
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(result.getFulfilments()).containsExactly(new Document("k", "updated"));
        assertThat(result.getUpdated()).isNotNull();
    }

    @Test
    void saveNotification_shouldThrowBadRequest_whenConcurrencyTokenMissingOnUpdate() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.DRAFT)));

        // When / Then
        assertThatThrownBy(() -> notificationService.saveNotification(
            NotificationDto.builder().referenceNumber(REFERENCE).build()))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("concurrencyToken is required");
    }

    @Test
    void saveNotification_shouldThrowBadRequest_whenExistingIsSubmitted() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.SUBMITTED)));

        // When / Then
        assertThatThrownBy(() -> notificationService.saveNotification(NotificationDto.builder()
            .referenceNumber(REFERENCE).concurrencyToken(2L).build()))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot replace notification content with status: SUBMITTED");
    }

    @Test
    void saveNotification_shouldThrowNotFound_whenReferenceDoesNotExist() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> notificationService.saveNotification(NotificationDto.builder()
            .referenceNumber(REFERENCE).concurrencyToken(2L).build()))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining(REFERENCE);
    }

    @Test
    void replace_shouldThrowBadRequest_whenNotificationIsDeleted() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.DELETED)));

        // When / Then
        assertThatThrownBy(() -> notificationService.replace(REFERENCE,
            NotificationDto.builder().concurrencyToken(2L).build()))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot replace notification content with status: DELETED");
    }

    @Test
    void submitNotification_shouldStampSubmittedAt_whenSubmittingFromDraft() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.DRAFT)));
        repositoryEchoesSaves();

        // When
        NotificationAggregate result = notificationService.submitNotification(REFERENCE);

        // Then
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(result.getSubmittedAt()).isNotNull();
    }

    @Test
    void submitNotification_shouldClearAmendBaselines_whenResubmittingFromAmend() {
        // Given a notification mid-amendment
        NotificationAggregate amending = stored(NotificationStatus.AMEND);
        amending.setSubmittedNotificationBaseline(new Notification());
        amending.setSubmittedFulfilmentsBaseline(List.of(new Document("k", "before")));
        when(notificationRepository.findByReferenceNumber(REFERENCE)).thenReturn(Optional.of(amending));
        repositoryEchoesSaves();

        // When
        NotificationAggregate result = notificationService.submitNotification(REFERENCE);

        // Then
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(result.getSubmittedNotificationBaseline()).isNull();
        assertThat(result.getSubmittedFulfilmentsBaseline()).isNull();
    }

    @Test
    void submitNotification_shouldThrowBadRequest_whenAlreadySubmitted() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.SUBMITTED)));

        // When / Then
        assertThatThrownBy(() -> notificationService.submitNotification(REFERENCE))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot submit notification with status: SUBMITTED");
    }

    @Test
    void amendNotification_shouldSnapshotContentAndFulfilmentsAsTheRestorePoint() {
        // Given
        NotificationAggregate submitted = stored(NotificationStatus.SUBMITTED);
        submitted.setFulfilments(List.of(new Document("k", "submitted")));
        Notification liveContent = submitted.getNotification();
        when(notificationRepository.findByReferenceNumber(REFERENCE)).thenReturn(Optional.of(submitted));
        repositoryEchoesSaves();

        // When
        NotificationAggregate result = notificationService.amendNotification(REFERENCE);

        // Then
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.AMEND);
        assertThat(result.getSubmittedNotificationBaseline()).isEqualTo(liveContent);
        assertThat(result.getSubmittedFulfilmentsBaseline())
            .containsExactly(new Document("k", "submitted"));
    }

    @Test
    void amendNotification_shouldSnapshotContentIndependentlyOfTheLiveContent() {
        NotificationAggregate submitted = stored(NotificationStatus.SUBMITTED);
        Notification liveContent = submitted.getNotification();
        when(notificationRepository.findByReferenceNumber(REFERENCE)).thenReturn(Optional.of(submitted));
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.amendNotification(REFERENCE);

        assertThat(result.getSubmittedNotificationBaseline()).isNotNull().isNotSameAs(liveContent);
    }

    @Test
    void amendNotification_shouldSnapshotFulfilmentsIndependently_soLaterEditsDoNotLeakIntoTheBaseline() {
        // Given
        NotificationAggregate submitted = stored(NotificationStatus.SUBMITTED);
        Document live = new Document("answers", new Document("fieldOne", "VALUE_ONE"));
        submitted.setFulfilments(List.of(live));
        when(notificationRepository.findByReferenceNumber(REFERENCE)).thenReturn(Optional.of(submitted));
        repositoryEchoesSaves();

        // When
        NotificationAggregate result = notificationService.amendNotification(REFERENCE);
        live.get("answers", Document.class).put("fieldOne", "Tulipa");

        // Then
        assertThat(result.getSubmittedFulfilmentsBaseline().getFirst()
            .get("answers", Document.class)).containsEntry("fieldOne", "VALUE_ONE");
    }

    @Test
    void amendNotification_shouldThrowBadRequest_whenNotSubmitted() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.DRAFT)));

        // When / Then
        assertThatThrownBy(() -> notificationService.amendNotification(REFERENCE))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot amend notification with status: DRAFT");
    }

    @Test
    void cancelAmendNotification_shouldRestoreTheBaselineAndPreserveSubmittedAt() {
        // Given
        Instant originalSubmittedAt = Instant.now().minus(2, ChronoUnit.DAYS);
        NotificationAggregate amending = stored(NotificationStatus.AMEND);
        amending.setSubmittedAt(originalSubmittedAt);
        amending.setFulfilments(List.of(new Document("k", "edited")));
        Notification editedContent = amending.getNotification();
        Notification baseline = new Notification();
        amending.setSubmittedNotificationBaseline(baseline);
        amending.setSubmittedFulfilmentsBaseline(List.of(new Document("k", "submitted")));
        when(notificationRepository.findByReferenceNumber(REFERENCE)).thenReturn(Optional.of(amending));
        repositoryEchoesSaves();

        // When
        NotificationAggregate result = notificationService.cancelAmendNotification(REFERENCE);

        // Then
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(result.getNotification())
            .isEqualTo(baseline)
            .isNotSameAs(baseline)
            .isNotSameAs(editedContent);
        assertThat(result.getFulfilments()).containsExactly(new Document("k", "submitted"));
        assertThat(result.getSubmittedNotificationBaseline()).isNull();
        assertThat(result.getSubmittedFulfilmentsBaseline()).isNull();
        assertThat(result.getSubmittedAt()).isEqualTo(originalSubmittedAt);
    }

    @Test
    void cancelAmendNotification_shouldThrowBadRequest_whenNoBaselineStored() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.AMEND)));

        // When / Then
        assertThatThrownBy(() -> notificationService.cancelAmendNotification(REFERENCE))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("no submitted baseline stored");
    }

    @Test
    void cancelAmendNotification_shouldThrowBadRequest_whenNotAmending() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.SUBMITTED)));

        // When / Then
        assertThatThrownBy(() -> notificationService.cancelAmendNotification(REFERENCE))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot cancel amendment for notification with status: SUBMITTED");
    }

    @Test
    void copyNotification_shouldCreateAFreshDraftCarryingTheFulfilments() {
        // Given
        NotificationAggregate source = stored(NotificationStatus.SUBMITTED);
        source.setFulfilments(List.of(new Document("k", "v")));
        when(notificationRepository.findByReferenceNumber(REFERENCE)).thenReturn(Optional.of(source));
        repositoryEchoesSaves();

        // When
        NotificationAggregate result = notificationService.copyNotification(REFERENCE, 2L);

        // Then
        assertThat(result.getReferenceNumber()).matches(ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(result.getFulfilments()).containsExactly(new Document("k", "v"));
    }

    @Test
    void copyNotification_shouldThrowOptimisticLockingFailure_whenSourceHasAdvanced() {
        // Given the caller's token is behind the stored one
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.SUBMITTED)));

        // When / Then
        assertThatThrownBy(() -> notificationService.copyNotification(REFERENCE, 1L))
            .isInstanceOf(OptimisticLockingFailureException.class)
            .hasMessageContaining("has advanced from expected concurrencyToken 1 to 2");
    }

    @Test
    void copyNotification_shouldThrowBadRequest_whenSourceIsDeleted() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.DELETED)));

        // When / Then
        assertThatThrownBy(() -> notificationService.copyNotification(REFERENCE, 2L))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot copy notification with status: DELETED");
    }

    @Test
    void softDeleteNotification_shouldTransitionToDeleted() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.SUBMITTED)));
        repositoryEchoesSaves();

        // When
        NotificationAggregate result = notificationService.softDeleteNotification(REFERENCE);

        // Then
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
    }

    @Test
    void softDeleteNotification_shouldBeIdempotent_whenAlreadyDeleted() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.DELETED)));

        // When
        NotificationAggregate result = notificationService.softDeleteNotification(REFERENCE);

        // Then — no second write
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
        verify(notificationRepository, never()).save(any());
    }

    private static List<String> messagesLoggedDuring(Runnable action) {
        return messagesOf(logEventsDuring(NotificationService.class, action));
    }

    @Test
    void softDeleteNotification_shouldLogTheReferenceAndNothingChanged_whenAlreadyDeleted() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.DELETED)));

        // When
        List<String> messages = messagesLoggedDuring(() -> notificationService.softDeleteNotification(REFERENCE));

        // Then
        assertThat(messages).containsExactly("Notification 26-ABC123 is already DELETED, nothing changed");
    }

    @Test
    void softDeleteNotification_shouldLogTheStatusChange() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.DRAFT)));
        repositoryEchoesSaves();

        // When
        List<String> messages = messagesLoggedDuring(() -> notificationService.softDeleteNotification(REFERENCE));

        // Then
        assertThat(messages).containsExactly("Notification 26-ABC123 status changed from DRAFT to DELETED");
    }

    @Test
    void submitNotification_shouldLogTheStatusChange() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.AMEND)));
        repositoryEchoesSaves();

        // When
        List<String> messages = messagesLoggedDuring(() -> notificationService.submitNotification(REFERENCE));

        // Then
        assertThat(messages).containsExactly("Notification 26-ABC123 status changed from AMEND to SUBMITTED");
    }

    @Test
    void amendNotification_shouldLogTheStatusChange() {
        // Given
        NotificationAggregate submitted = stored(NotificationStatus.SUBMITTED);
        submitted.setFulfilments(List.of(new Document("k", "submitted")));
        when(notificationRepository.findByReferenceNumber(REFERENCE)).thenReturn(Optional.of(submitted));
        repositoryEchoesSaves();

        // When
        List<String> messages = messagesLoggedDuring(() -> notificationService.amendNotification(REFERENCE));

        // Then
        assertThat(messages).containsExactly("Notification 26-ABC123 status changed from SUBMITTED to AMEND");
    }

    @Test
    void cancelAmendNotification_shouldLogTheStatusChange() {
        // Given
        NotificationAggregate amending = stored(NotificationStatus.AMEND);
        amending.setSubmittedNotificationBaseline(new Notification());
        amending.setSubmittedFulfilmentsBaseline(List.of(new Document("k", "submitted")));
        when(notificationRepository.findByReferenceNumber(REFERENCE)).thenReturn(Optional.of(amending));
        repositoryEchoesSaves();

        // When
        List<String> messages = messagesLoggedDuring(() -> notificationService.cancelAmendNotification(REFERENCE));

        // Then
        assertThat(messages).containsExactly("Notification 26-ABC123 status changed from AMEND to SUBMITTED");
    }

    @Test
    void replace_shouldLogOnlyTheUpdate_whenTheStatusStaysTheSame() {
        // Given
        when(notificationRepository.findByReferenceNumber(REFERENCE))
            .thenReturn(Optional.of(stored(NotificationStatus.DRAFT)));
        repositoryEchoesSaves();

        // When
        List<String> messages = messagesLoggedDuring(() -> notificationService.replace(REFERENCE,
            NotificationDto.builder().concurrencyToken(2L).build()));

        // Then
        assertThat(messages).containsExactly("Updating notification 26-ABC123");
    }

    @Test
    void logExpiryStamping_shouldGiveDaysAndEnvironment_whenStampingIsOn() {
        // When
        List<String> messages = messagesLoggedDuring(newService(ttlConfig(7, "dev"))::logExpiryStamping);

        // Then
        assertThat(messages).containsExactly("Notification expiry stamping is on: 7 days in environment dev");
    }

    @Test
    void logExpiryStamping_shouldNameProdAsTheReason_whenDaysAreSetInProd() {
        // When
        List<String> messages = messagesLoggedDuring(newService(ttlConfig(7, "prod"))::logExpiryStamping);

        // Then
        assertThat(messages).containsExactly("Notification expiry stamping is off: environment prod is prod");
    }

    @Test
    void logExpiryStamping_shouldNameMissingDaysAsTheReason_whenNoDaysAreSetOutsideProd() {
        // When
        List<String> messages = messagesLoggedDuring(newService(ttlConfig(NO_TTL_DAYS, "dev"))::logExpiryStamping);

        // Then
        assertThat(messages).containsExactly("Notification expiry stamping is off: no days set in environment dev");
    }

    @Test
    void logExpiryStamping_shouldListenForTheApplicationBeingReady() throws NoSuchMethodException {
        // When
        EventListener listener = NotificationService.class
            .getMethod("logExpiryStamping")
            .getAnnotation(EventListener.class);

        // Then
        assertThat(listener.value()).containsExactly(ApplicationReadyEvent.class);
    }

    private void onlyReferenceExistsAmong(List<String> requestedReferences) {
        when(notificationRepository.findAllReferencesByReferenceNumberIn(requestedReferences))
            .thenReturn(List.of(() -> REFERENCE));
    }

    private Audit savedAudit() {
        ArgumentCaptor<Audit> auditCaptor = ArgumentCaptor.forClass(Audit.class);
        verify(auditRepository).save(auditCaptor.capture());
        return auditCaptor.getValue();
    }

    private Throwable deleteWithAMissingReference() {
        List<String> requestedReferences = List.of(REFERENCE, "26-MSSNG1");
        onlyReferenceExistsAmong(requestedReferences);

        return catchThrowable(
            () -> notificationService.deleteByReferenceNumbers(requestedReferences, AUDIT_CONTEXT));
    }

    @Test
    void deleteByReferenceNumbers_shouldDeleteAll_whenEveryReferenceExists() {
        onlyReferenceExistsAmong(List.of(REFERENCE));

        notificationService.deleteByReferenceNumbers(List.of(REFERENCE), AUDIT_CONTEXT);

        verify(notificationRepository).deleteAllByReferenceNumberIn(List.of(REFERENCE));
    }

    @Test
    void deleteByReferenceNumbers_shouldAuditSuccess_whenEveryReferenceExists() {
        onlyReferenceExistsAmong(List.of(REFERENCE));

        notificationService.deleteByReferenceNumbers(List.of(REFERENCE), AUDIT_CONTEXT);

        Audit audit = savedAudit();
        assertThat(audit.getResult()).isEqualTo(Result.SUCCESS);
        assertThat(audit.getAction()).isEqualTo(Action.DELETE_NOTIFICATIONS);
        assertThat(audit.getTraceId()).isEqualTo("trace-1");
        assertThat(audit.getUserId()).isEqualTo("user-1");
    }

    @Test
    void deleteByReferenceNumbers_shouldLogTheCountAndReferenceNumbers_whenEveryReferenceExists() {
        onlyReferenceExistsAmong(List.of(REFERENCE));

        List<String> messages = messagesLoggedDuring(
            () -> notificationService.deleteByReferenceNumbers(List.of(REFERENCE), AUDIT_CONTEXT));

        assertThat(messages).containsExactly("Deleting 1 notifications: [26-ABC123]");
    }

    @Test
    void deleteByReferenceNumbers_shouldThrowNotFoundNamingTheMissingReference_whenAnyReferenceIsMissing() {
        Throwable thrown = deleteWithAMissingReference();

        assertThat(thrown).isInstanceOf(NotFoundException.class).hasMessageContaining("26-MSSNG1");
    }

    @Test
    void deleteByReferenceNumbers_shouldDeleteNothing_whenAnyReferenceIsMissing() {
        deleteWithAMissingReference();

        verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
    }

    @Test
    void deleteByReferenceNumbers_shouldAuditFailure_whenAnyReferenceIsMissing() {
        deleteWithAMissingReference();

        assertThat(savedAudit().getResult()).isEqualTo(Result.FAILURE);
    }

    @Test
    void deleteByReferenceNumbers_shouldBeANoOp_whenGivenAnEmptyList() {
        // When
        notificationService.deleteByReferenceNumbers(List.of(), AUDIT_CONTEXT);

        // Then
        verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
        verify(auditRepository, never()).save(any());
    }

    @Test
    void findFulfilmentsView_shouldThrowNotFound_whenReferenceDoesNotExist() {
        // Given
        when(notificationRepository.findFulfilmentsViewByReferenceNumber(REFERENCE))
            .thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> notificationService.findFulfilmentsView(REFERENCE))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining(REFERENCE);
    }
}
