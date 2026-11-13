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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import uk.gov.defra.trade.imports.plants.audit.Action;
import uk.gov.defra.trade.imports.plants.audit.Audit;
import uk.gov.defra.trade.imports.plants.audit.AuditRepository;
import uk.gov.defra.trade.imports.plants.audit.Result;
import uk.gov.defra.trade.imports.plants.configuration.NotificationTtlConfig;
import uk.gov.defra.trade.imports.plants.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.plants.exceptions.ForbiddenException;
import uk.gov.defra.trade.imports.plants.exceptions.NotFoundException;
import uk.gov.defra.trade.imports.plants.testsupport.TraderOrganisationTestSupport;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final String ORGANISATION_ID = TraderOrganisationTestSupport.ORGANISATION_ID;

    private static final String REFERENCE = "26-ABC123";
    private static final Integer NO_TTL_DAYS = null;
    private static final AuditContext AUDIT_CONTEXT = new AuditContext("trace-1", "user-1");
    private static final Instant ORIGINAL_SUBMITTED_AT = Instant.parse("2026-07-14T10:30:15Z");

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
            .organisationId(ORGANISATION_ID)
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

    private void repositoryHolds(NotificationAggregate storedAggregate) {
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID)).thenReturn(Optional.of(storedAggregate));
    }

    @Test
    void saveNotification_shouldCreateDraftWithGeneratedReference_whenNoReferenceSupplied() {
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.saveNotification(
            NotificationDto.builder().fulfilments(List.of(new Document("k", "v"))).build(), ORGANISATION_ID);

        assertThat(result.getReferenceNumber()).matches(ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(result.getOrganisationId()).isEqualTo(ORGANISATION_ID);
        assertThat(result.getCreated()).isNotNull();
        assertThat(result.getFulfilments()).containsExactly(new Document("k", "v"));
    }

    @Test
    void saveNotification_shouldNotStampExpireAt_whenNoTtlConfigured() {
        notificationService = newService(ttlConfig(NO_TTL_DAYS, "dev"));
        repositoryEchoesSaves();

        NotificationAggregate result =
            notificationService.saveNotification(NotificationDto.builder().build(), ORGANISATION_ID);

        assertThat(result.getExpireAt()).isNull();
    }

    @Test
    void saveNotification_shouldNotStampExpireAt_whenEnvironmentIsProd() {
        notificationService = newService(ttlConfig(7, "prod"));
        repositoryEchoesSaves();

        NotificationAggregate result =
            notificationService.saveNotification(NotificationDto.builder().build(), ORGANISATION_ID);

        assertThat(result.getExpireAt()).isNull();
    }

    @Test
    void saveNotification_shouldStampExpireAtRelativeToCreated_whenNonProdAndTtlConfigured() {
        notificationService = newService(ttlConfig(7, "dev"));
        repositoryEchoesSaves();

        NotificationAggregate result =
            notificationService.saveNotification(NotificationDto.builder().build(), ORGANISATION_ID);

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
                notificationService.saveNotification(NotificationDto.builder().build(), ORGANISATION_ID);

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

        return catchThrowable(() -> notificationService.saveNotification(dto, ORGANISATION_ID));
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
        List<String> messages = messagesLoggedDuring(
            () -> saveWhoseThreeAttemptsCollide(new DuplicateKeyException("last collision")));

        assertThat(messages).containsExactly(
            "Reference number " + attemptedReferences.get(0) + " collided on persistence attempt 1/3",
            "Reference number " + attemptedReferences.get(1) + " collided on persistence attempt 2/3",
            "Reference number " + attemptedReferences.get(2) + " collided on persistence attempt 3/3");
    }

    @Test
    void saveNotification_shouldLogTheThrowableOfEachCollision_whenAllPersistenceAttemptsCollide() {
        List<ILoggingEvent> events = logEventsDuring(NotificationService.class,
            () -> saveWhoseThreeAttemptsCollide(new DuplicateKeyException("last collision")));

        assertThat(events)
            .extracting(event -> event.getThrowableProxy().getMessage())
            .containsExactly("first collision", "second collision", "last collision");
    }

    @Test
    void saveNotification_shouldKeepTheLastCollisionAsTheCause_whenAllPersistenceAttemptsCollide() {
        DuplicateKeyException lastCollision = new DuplicateKeyException("last collision");

        Throwable thrown = saveWhoseThreeAttemptsCollide(lastCollision);

        assertThat(thrown).isInstanceOf(IllegalStateException.class).hasCauseReference(lastCollision);
    }

    @Test
    void saveNotification_shouldUpdateExistingDraft_whenReferenceSupplied() {
        repositoryHolds(stored(NotificationStatus.DRAFT));
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.saveNotification(NotificationDto.builder()
            .referenceNumber(REFERENCE)
            .concurrencyToken(2L)
            .fulfilments(List.of(new Document("k", "updated")))
            .build(), ORGANISATION_ID);

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(result.getFulfilments()).containsExactly(new Document("k", "updated"));
        assertThat(result.getOrganisationId()).isEqualTo(ORGANISATION_ID);
        assertThat(result.getUpdated()).isNotNull();
    }

    @Test
    void saveNotification_shouldThrowBadRequest_whenConcurrencyTokenMissingOnUpdate() {
        repositoryHolds(stored(NotificationStatus.DRAFT));

        assertThatThrownBy(() -> notificationService.saveNotification(
            NotificationDto.builder().referenceNumber(REFERENCE).build(), ORGANISATION_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("concurrencyToken is required");
    }

    @Test
    void saveNotification_shouldThrowBadRequest_whenExistingIsSubmitted() {
        repositoryHolds(stored(NotificationStatus.SUBMITTED));

        assertThatThrownBy(() -> notificationService.saveNotification(NotificationDto.builder()
            .referenceNumber(REFERENCE).concurrencyToken(2L).build(), ORGANISATION_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot replace notification content with status: SUBMITTED");
    }

    @Test
    void saveNotification_shouldThrowNotFound_whenReferenceDoesNotExist() {
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.saveNotification(NotificationDto.builder()
            .referenceNumber(REFERENCE).concurrencyToken(2L).build(), ORGANISATION_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining(REFERENCE);
    }

    @Test
    void replace_shouldThrowBadRequest_whenNotificationIsDeleted() {
        repositoryHolds(stored(NotificationStatus.DELETED));

        assertThatThrownBy(() -> notificationService.replace(REFERENCE,
            NotificationDto.builder().concurrencyToken(2L).build(), ORGANISATION_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot replace notification content with status: DELETED");
    }

    @Test
    void submitNotification_shouldStampSubmittedAt_whenSubmittingFromDraft() {
        repositoryHolds(stored(NotificationStatus.DRAFT));
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.submitNotification(REFERENCE, ORGANISATION_ID);

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(result.getSubmittedAt()).isNotNull();
    }

    @Test
    void submitNotification_shouldClearAmendBaselines_whenResubmittingFromAmend() {
        NotificationAggregate amending = stored(NotificationStatus.AMEND);
        amending.setSubmittedNotificationBaseline(new Notification());
        amending.setSubmittedFulfilmentsBaseline(List.of(new Document("k", "before")));
        repositoryHolds(amending);
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.submitNotification(REFERENCE, ORGANISATION_ID);

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(result.getSubmittedNotificationBaseline()).isNull();
        assertThat(result.getSubmittedFulfilmentsBaseline()).isNull();
    }

    @Test
    void submitNotification_shouldThrowBadRequest_whenAlreadySubmitted() {
        repositoryHolds(stored(NotificationStatus.SUBMITTED));

        assertThatThrownBy(() -> notificationService.submitNotification(REFERENCE, ORGANISATION_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot submit notification with status: SUBMITTED");
    }

    @Test
    void amendNotification_shouldSnapshotContentAndFulfilmentsAsTheRestorePoint() {
        NotificationAggregate submitted = stored(NotificationStatus.SUBMITTED);
        submitted.setFulfilments(List.of(new Document("k", "submitted")));
        Notification liveContent = submitted.getNotification();
        repositoryHolds(submitted);
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.amendNotification(REFERENCE, ORGANISATION_ID);

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.AMEND);
        assertThat(result.getSubmittedNotificationBaseline()).isEqualTo(liveContent);
        assertThat(result.getSubmittedFulfilmentsBaseline())
            .containsExactly(new Document("k", "submitted"));
    }

    @Test
    void amendNotification_shouldSnapshotContentIndependentlyOfTheLiveContent() {
        NotificationAggregate submitted = stored(NotificationStatus.SUBMITTED);
        Notification liveContent = submitted.getNotification();
        repositoryHolds(submitted);
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.amendNotification(REFERENCE, ORGANISATION_ID);

        assertThat(result.getSubmittedNotificationBaseline()).isNotNull().isNotSameAs(liveContent);
    }

    @Test
    void amendNotification_shouldSnapshotFulfilmentsIndependently_soLaterEditsDoNotLeakIntoTheBaseline() {
        NotificationAggregate submitted = stored(NotificationStatus.SUBMITTED);
        Document live = new Document("answers", new Document("fieldOne", "VALUE_ONE"));
        submitted.setFulfilments(List.of(live));
        repositoryHolds(submitted);
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.amendNotification(REFERENCE, ORGANISATION_ID);
        live.get("answers", Document.class).put("fieldOne", "Tulipa");

        assertThat(result.getSubmittedFulfilmentsBaseline().getFirst()
            .get("answers", Document.class)).containsEntry("fieldOne", "VALUE_ONE");
    }

    @Test
    void amendNotification_shouldThrowBadRequest_whenNotSubmitted() {
        repositoryHolds(stored(NotificationStatus.DRAFT));

        assertThatThrownBy(() -> notificationService.amendNotification(REFERENCE, ORGANISATION_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot amend notification with status: DRAFT");
    }

    private NotificationAggregate repositoryHoldsAnAmendmentWithBaselines() {
        NotificationAggregate amending = stored(NotificationStatus.AMEND);
        amending.setSubmittedAt(ORIGINAL_SUBMITTED_AT);
        amending.setFulfilments(List.of(new Document("k", "edited")));
        amending.setSubmittedNotificationBaseline(new Notification());
        amending.setSubmittedFulfilmentsBaseline(List.of(new Document("k", "submitted")));
        repositoryHolds(amending);
        repositoryEchoesSaves();
        return amending;
    }

    @Test
    void cancelAmendNotification_shouldReturnToSubmittedWithTheBaselineContentAndFulfilments() {
        NotificationAggregate amending = repositoryHoldsAnAmendmentWithBaselines();
        Notification editedContent = amending.getNotification();
        Notification baseline = amending.getSubmittedNotificationBaseline();

        NotificationAggregate result = notificationService.cancelAmendNotification(REFERENCE, ORGANISATION_ID);

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(result.getNotification())
            .isEqualTo(baseline)
            .isNotSameAs(baseline)
            .isNotSameAs(editedContent);
        assertThat(result.getFulfilments()).containsExactly(new Document("k", "submitted"));
    }

    @Test
    void cancelAmendNotification_shouldClearBothBaselines() {
        repositoryHoldsAnAmendmentWithBaselines();

        NotificationAggregate result = notificationService.cancelAmendNotification(REFERENCE, ORGANISATION_ID);

        assertThat(result.getSubmittedNotificationBaseline()).isNull();
        assertThat(result.getSubmittedFulfilmentsBaseline()).isNull();
    }

    @Test
    void cancelAmendNotification_shouldKeepTheSubmittedAtOfTheSubmissionItReturnsTo() {
        repositoryHoldsAnAmendmentWithBaselines();

        NotificationAggregate result = notificationService.cancelAmendNotification(REFERENCE, ORGANISATION_ID);

        assertThat(result.getSubmittedAt()).isEqualTo(ORIGINAL_SUBMITTED_AT);
    }

    @Test
    void cancelAmendNotification_shouldThrowBadRequest_whenNoBaselineStored() {
        repositoryHolds(stored(NotificationStatus.AMEND));

        assertThatThrownBy(() -> notificationService.cancelAmendNotification(REFERENCE, ORGANISATION_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("no submitted baseline stored");
    }

    @Test
    void cancelAmendNotification_shouldThrowBadRequest_whenNotAmending() {
        repositoryHolds(stored(NotificationStatus.SUBMITTED));

        assertThatThrownBy(() -> notificationService.cancelAmendNotification(REFERENCE, ORGANISATION_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot cancel amendment for notification with status: SUBMITTED");
    }

    @Test
    void copyNotification_shouldCreateAFreshDraftCarryingTheFulfilments() {
        NotificationAggregate source = stored(NotificationStatus.SUBMITTED);
        source.setFulfilments(List.of(new Document("k", "v")));
        repositoryHolds(source);
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.copyNotification(REFERENCE, 2L, ORGANISATION_ID);

        assertThat(result.getReferenceNumber()).matches(ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(result.getOrganisationId()).isEqualTo(ORGANISATION_ID);
        assertThat(result.getFulfilments()).containsExactly(new Document("k", "v"));
    }

    @Test
    void copyNotification_shouldThrowOptimisticLockingFailure_whenSourceHasAdvanced() {
        repositoryHolds(stored(NotificationStatus.SUBMITTED));

        assertThatThrownBy(() -> notificationService.copyNotification(REFERENCE, 1L, ORGANISATION_ID))
            .isInstanceOf(OptimisticLockingFailureException.class)
            .hasMessageContaining("has advanced from expected concurrencyToken 1 to 2");
    }

    @Test
    void copyNotification_shouldThrowBadRequest_whenSourceIsDeleted() {
        repositoryHolds(stored(NotificationStatus.DELETED));

        assertThatThrownBy(() -> notificationService.copyNotification(REFERENCE, 2L, ORGANISATION_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot copy notification with status: DELETED");
    }

    @Test
    void softDeleteNotification_shouldTransitionToDeleted() {
        repositoryHolds(stored(NotificationStatus.SUBMITTED));
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.softDeleteNotification(REFERENCE, ORGANISATION_ID);

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
    }

    @Test
    void softDeleteNotification_shouldBeIdempotent_whenAlreadyDeleted() {
        repositoryHolds(stored(NotificationStatus.DELETED));

        NotificationAggregate result = notificationService.softDeleteNotification(REFERENCE, ORGANISATION_ID);

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
        verify(notificationRepository, never()).save(any());
    }

    private static List<String> messagesLoggedDuring(Runnable action) {
        return messagesOf(logEventsDuring(NotificationService.class, action));
    }

    @Test
    void softDeleteNotification_shouldLogTheReferenceAndNothingChanged_whenAlreadyDeleted() {
        repositoryHolds(stored(NotificationStatus.DELETED));

        List<String> messages = messagesLoggedDuring(() -> notificationService.softDeleteNotification(REFERENCE, ORGANISATION_ID));

        assertThat(messages).containsExactly("Notification 26-ABC123 is already DELETED, nothing changed");
    }

    @Test
    void softDeleteNotification_shouldLogTheStatusChange() {
        repositoryHolds(stored(NotificationStatus.DRAFT));
        repositoryEchoesSaves();

        List<String> messages = messagesLoggedDuring(() -> notificationService.softDeleteNotification(REFERENCE, ORGANISATION_ID));

        assertThat(messages).containsExactly("Notification 26-ABC123 status changed from DRAFT to DELETED");
    }

    @Test
    void submitNotification_shouldLogTheStatusChange() {
        repositoryHolds(stored(NotificationStatus.AMEND));
        repositoryEchoesSaves();

        List<String> messages = messagesLoggedDuring(() -> notificationService.submitNotification(REFERENCE, ORGANISATION_ID));

        assertThat(messages).containsExactly("Notification 26-ABC123 status changed from AMEND to SUBMITTED");
    }

    @Test
    void amendNotification_shouldLogTheStatusChange() {
        NotificationAggregate submitted = stored(NotificationStatus.SUBMITTED);
        submitted.setFulfilments(List.of(new Document("k", "submitted")));
        repositoryHolds(submitted);
        repositoryEchoesSaves();

        List<String> messages = messagesLoggedDuring(() -> notificationService.amendNotification(REFERENCE, ORGANISATION_ID));

        assertThat(messages).containsExactly("Notification 26-ABC123 status changed from SUBMITTED to AMEND");
    }

    @Test
    void cancelAmendNotification_shouldLogTheStatusChange() {
        repositoryHoldsAnAmendmentWithBaselines();

        List<String> messages = messagesLoggedDuring(() -> notificationService.cancelAmendNotification(REFERENCE, ORGANISATION_ID));

        assertThat(messages).containsExactly("Notification 26-ABC123 status changed from AMEND to SUBMITTED");
    }

    @Test
    void saveNotification_shouldLogOnlyTheSavedReference_whenCreating() {
        repositoryEchoesSaves();
        List<NotificationAggregate> created = new ArrayList<>();

        List<String> messages = messagesLoggedDuring(
            () -> created.add(notificationService.saveNotification(NotificationDto.builder().build(), ORGANISATION_ID)));

        assertThat(messages).containsExactly(
            "Notification saved with reference number: " + created.getFirst().getReferenceNumber());
    }

    @Test
    void replace_shouldLogOnlyTheUpdate_whenTheStatusStaysTheSame() {
        repositoryHolds(stored(NotificationStatus.DRAFT));
        repositoryEchoesSaves();

        List<String> messages = messagesLoggedDuring(() -> notificationService.replace(REFERENCE,
            NotificationDto.builder().concurrencyToken(2L).build(), ORGANISATION_ID));

        assertThat(messages).containsExactly("Updating notification 26-ABC123");
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
        notificationService.deleteByReferenceNumbers(List.of(), AUDIT_CONTEXT);

        verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
        verify(auditRepository, never()).save(any());
    }

    @Test
    void findFulfilmentsView_shouldThrowNotFound_whenReferenceDoesNotExist() {
        when(notificationRepository.findFulfilmentsViewByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID))
            .thenReturn(Optional.empty());
        when(notificationRepository.existsByReferenceNumber(REFERENCE)).thenReturn(false);

        assertThatThrownBy(() -> notificationService.findFulfilmentsView(REFERENCE, ORGANISATION_ID))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining(REFERENCE);
    }

    @Test
    void findFulfilmentsView_shouldThrowForbidden_whenReferenceBelongsToAnotherOrganisation() {
        when(notificationRepository.findFulfilmentsViewByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID))
            .thenReturn(Optional.empty());
        when(notificationRepository.existsByReferenceNumber(REFERENCE)).thenReturn(true);

        assertThatThrownBy(() -> notificationService.findFulfilmentsView(REFERENCE, ORGANISATION_ID))
            .isInstanceOf(ForbiddenException.class)
            .hasMessageContaining(REFERENCE)
            .hasMessageContaining(ORGANISATION_ID);
    }
}
