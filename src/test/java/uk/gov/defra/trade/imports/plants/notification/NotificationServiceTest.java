package uk.gov.defra.trade.imports.plants.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
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
    private static final AuditContext AUDIT_CONTEXT = new AuditContext("trace-1", "user-1");

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private AuditRepository auditRepository;

    @Mock
    private NotificationContentMapper notificationContentMapper;

    @Mock
    private ReferenceNumberGenerator referenceNumberGenerator;

    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationService = newService(ttlConfig(null, "local"));
    }

    private NotificationService newService(NotificationTtlConfig ttlConfig) {
        return new NotificationService(
            notificationRepository,
            auditRepository,
            new NotificationCopyMapper(),
            notificationContentMapper,
            referenceNumberGenerator,
            ttlConfig,
            25,
            50);
    }

    private static NotificationTtlConfig ttlConfig(Integer days, String environment) {
        return new NotificationTtlConfig(days, environment, new NotificationTtlConfig.Sweep(
            false, 3_600_000, 10, Duration.ofSeconds(1), Duration.ofSeconds(30)));
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

    @Test
    void saveNotification_shouldCreateDraftWithGeneratedReference_whenNoReferenceSupplied() {
        when(referenceNumberGenerator.generate()).thenReturn(REFERENCE);
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.saveNotification(
            NotificationDto.builder().fulfilments(List.of(new Document("k", "v"))).build(), ORGANISATION_ID);

        assertThat(result.getReferenceNumber()).isEqualTo(REFERENCE);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(result.getOrganisationId()).isEqualTo(ORGANISATION_ID);
        assertThat(result.getCreated()).isNotNull();
        assertThat(result.getFulfilments()).containsExactly(new Document("k", "v"));
    }

    @Test
    void saveNotification_shouldNotStampExpireAt_whenNoTtlConfigured() {
        when(referenceNumberGenerator.generate()).thenReturn(REFERENCE);
        repositoryEchoesSaves();

        NotificationAggregate result =
            notificationService.saveNotification(NotificationDto.builder().build(), ORGANISATION_ID);

        assertThat(result.getExpireAt()).isNull();
    }

    @Test
    void saveNotification_shouldNotStampExpireAt_whenEnvironmentIsProd() {
        notificationService = newService(ttlConfig(7, "prod"));
        when(referenceNumberGenerator.generate()).thenReturn(REFERENCE);
        repositoryEchoesSaves();

        NotificationAggregate result =
            notificationService.saveNotification(NotificationDto.builder().build(), ORGANISATION_ID);

        assertThat(result.getExpireAt()).isNull();
    }

    @Test
    void saveNotification_shouldStampExpireAtRelativeToCreated_whenNonProdAndTtlConfigured() {
        notificationService = newService(ttlConfig(7, "dev"));
        when(referenceNumberGenerator.generate()).thenReturn(REFERENCE);
        repositoryEchoesSaves();

        NotificationAggregate result =
            notificationService.saveNotification(NotificationDto.builder().build(), ORGANISATION_ID);

        assertThat(result.getExpireAt()).isEqualTo(result.getCreated().plus(7, ChronoUnit.DAYS));
    }

    @Test
    void saveNotification_shouldStampExpireAtAnExactNumberOfElapsedDaysAfterCreated_whenJvmDefaultZoneObservesDst() {
        TimeZone originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));
        try {
            notificationService = newService(ttlConfig(7, "dev"));
            when(referenceNumberGenerator.generate()).thenReturn(REFERENCE);
            repositoryEchoesSaves();

            NotificationAggregate result =
                notificationService.saveNotification(NotificationDto.builder().build(), ORGANISATION_ID);

            assertThat(Duration.between(result.getCreated(), result.getExpireAt()))
                .isEqualTo(Duration.ofHours(7 * 24));
        } finally {
            TimeZone.setDefault(originalTimeZone);
        }
    }

    @Test
    void saveNotification_shouldUpdateExistingDraft_whenReferenceSupplied() {
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID))
            .thenReturn(Optional.of(stored(NotificationStatus.DRAFT)));
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
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID))
            .thenReturn(Optional.of(stored(NotificationStatus.DRAFT)));

        assertThatThrownBy(() -> notificationService.saveNotification(
            NotificationDto.builder().referenceNumber(REFERENCE).build(), ORGANISATION_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("concurrencyToken is required");
    }

    @Test
    void saveNotification_shouldThrowBadRequest_whenExistingIsSubmitted() {
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID))
            .thenReturn(Optional.of(stored(NotificationStatus.SUBMITTED)));

        assertThatThrownBy(() -> notificationService.saveNotification(NotificationDto.builder()
            .referenceNumber(REFERENCE).concurrencyToken(2L).build(), ORGANISATION_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot save notification with status: SUBMITTED");
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
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID))
            .thenReturn(Optional.of(stored(NotificationStatus.DELETED)));

        assertThatThrownBy(() -> notificationService.replace(REFERENCE,
            NotificationDto.builder().concurrencyToken(2L).build(), ORGANISATION_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot replace notification content with status: DELETED");
    }

    @Test
    void submitNotification_shouldStampSubmittedAt_whenSubmittingFromDraft() {
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID))
            .thenReturn(Optional.of(stored(NotificationStatus.DRAFT)));
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
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID)).thenReturn(Optional.of(amending));
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.submitNotification(REFERENCE, ORGANISATION_ID);

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(result.getSubmittedNotificationBaseline()).isNull();
        assertThat(result.getSubmittedFulfilmentsBaseline()).isNull();
    }

    @Test
    void submitNotification_shouldThrowBadRequest_whenAlreadySubmitted() {
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID))
            .thenReturn(Optional.of(stored(NotificationStatus.SUBMITTED)));

        assertThatThrownBy(() -> notificationService.submitNotification(REFERENCE, ORGANISATION_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot submit notification with status: SUBMITTED");
    }

    @Test
    void amendNotification_shouldSnapshotContentAndFulfilmentsAsTheRestorePoint() {
        NotificationAggregate submitted = stored(NotificationStatus.SUBMITTED);
        submitted.setFulfilments(List.of(new Document("k", "submitted")));
        Notification clone = new Notification();
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID)).thenReturn(Optional.of(submitted));
        when(notificationContentMapper.deepClone(submitted.getNotification())).thenReturn(clone);
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.amendNotification(REFERENCE, ORGANISATION_ID);

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.AMEND);
        assertThat(result.getOrganisationId()).isEqualTo(ORGANISATION_ID);
        assertThat(result.getSubmittedNotificationBaseline()).isSameAs(clone);
        assertThat(result.getSubmittedFulfilmentsBaseline())
            .containsExactly(new Document("k", "submitted"));
    }

    @Test
    void amendNotification_shouldSnapshotFulfilmentsIndependently_soLaterEditsDoNotLeakIntoTheBaseline() {
        NotificationAggregate submitted = stored(NotificationStatus.SUBMITTED);
        Document live = new Document("answers", new Document("fieldOne", "VALUE_ONE"));
        submitted.setFulfilments(List.of(live));
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID)).thenReturn(Optional.of(submitted));
        when(notificationContentMapper.deepClone(any())).thenReturn(new Notification());
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.amendNotification(REFERENCE, ORGANISATION_ID);
        live.get("answers", Document.class).put("fieldOne", "Tulipa");

        assertThat(result.getSubmittedFulfilmentsBaseline().getFirst()
            .get("answers", Document.class)).containsEntry("fieldOne", "VALUE_ONE");
    }

    @Test
    void amendNotification_shouldThrowBadRequest_whenNotSubmitted() {
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID))
            .thenReturn(Optional.of(stored(NotificationStatus.DRAFT)));

        assertThatThrownBy(() -> notificationService.amendNotification(REFERENCE, ORGANISATION_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot amend notification with status: DRAFT");
    }

    @Test
    void cancelAmendNotification_shouldRestoreTheBaselineAndPreserveSubmittedAt() {
        Instant originalSubmittedAt = Instant.now().minus(2, ChronoUnit.DAYS);
        NotificationAggregate amending = stored(NotificationStatus.AMEND);
        amending.setSubmittedAt(originalSubmittedAt);
        amending.setFulfilments(List.of(new Document("k", "edited")));
        Notification baseline = new Notification();
        amending.setSubmittedNotificationBaseline(baseline);
        amending.setSubmittedFulfilmentsBaseline(List.of(new Document("k", "submitted")));
        Notification restored = new Notification();
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID)).thenReturn(Optional.of(amending));
        when(notificationContentMapper.deepClone(baseline)).thenReturn(restored);
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.cancelAmendNotification(REFERENCE, ORGANISATION_ID);

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(result.getNotification()).isSameAs(restored);
        assertThat(result.getFulfilments()).containsExactly(new Document("k", "submitted"));
        assertThat(result.getSubmittedNotificationBaseline()).isNull();
        assertThat(result.getSubmittedFulfilmentsBaseline()).isNull();
        assertThat(result.getSubmittedAt()).isEqualTo(originalSubmittedAt);
    }

    @Test
    void cancelAmendNotification_shouldThrowBadRequest_whenNoBaselineStored() {
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID))
            .thenReturn(Optional.of(stored(NotificationStatus.AMEND)));

        assertThatThrownBy(() -> notificationService.cancelAmendNotification(REFERENCE, ORGANISATION_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("no submitted baseline stored");
    }

    @Test
    void cancelAmendNotification_shouldThrowBadRequest_whenNotAmending() {
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID))
            .thenReturn(Optional.of(stored(NotificationStatus.SUBMITTED)));

        assertThatThrownBy(() -> notificationService.cancelAmendNotification(REFERENCE, ORGANISATION_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot cancel amendment for notification with status: SUBMITTED");
    }

    @Test
    void copyNotification_shouldCreateAFreshDraftCarryingTheFulfilments() {
        NotificationAggregate source = stored(NotificationStatus.SUBMITTED);
        source.setFulfilments(List.of(new Document("k", "v")));
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID)).thenReturn(Optional.of(source));
        when(referenceNumberGenerator.generate()).thenReturn("26-XYZ789");
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.copyNotification(REFERENCE, 2L, ORGANISATION_ID);

        assertThat(result.getReferenceNumber()).isEqualTo("26-XYZ789");
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(result.getOrganisationId()).isEqualTo(ORGANISATION_ID);
        assertThat(result.getFulfilments()).containsExactly(new Document("k", "v"));
    }

    @Test
    void copyNotification_shouldThrowOptimisticLockingFailure_whenSourceHasAdvanced() {
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID))
            .thenReturn(Optional.of(stored(NotificationStatus.SUBMITTED)));

        assertThatThrownBy(() -> notificationService.copyNotification(REFERENCE, 1L, ORGANISATION_ID))
            .isInstanceOf(OptimisticLockingFailureException.class)
            .hasMessageContaining("has advanced from expected concurrencyToken 1 to 2");
    }

    @Test
    void copyNotification_shouldThrowBadRequest_whenSourceIsDeleted() {
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID))
            .thenReturn(Optional.of(stored(NotificationStatus.DELETED)));

        assertThatThrownBy(() -> notificationService.copyNotification(REFERENCE, 2L, ORGANISATION_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Cannot copy notification with status: DELETED");
    }

    @Test
    void softDeleteNotification_shouldTransitionToDeleted() {
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID))
            .thenReturn(Optional.of(stored(NotificationStatus.SUBMITTED)));
        repositoryEchoesSaves();

        NotificationAggregate result = notificationService.softDeleteNotification(REFERENCE, ORGANISATION_ID);

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
    }

    @Test
    void softDeleteNotification_shouldBeIdempotent_whenAlreadyDeleted() {
        when(notificationRepository.findByReferenceNumberAndOrganisationId(REFERENCE, ORGANISATION_ID))
            .thenReturn(Optional.of(stored(NotificationStatus.DELETED)));

        NotificationAggregate result = notificationService.softDeleteNotification(REFERENCE, ORGANISATION_ID);

        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DELETED);
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void deleteByReferenceNumbers_shouldDeleteAllAndAuditSuccess_whenEveryReferenceExists() {
        when(notificationRepository.findAllByReferenceNumberIn(List.of(REFERENCE)))
            .thenReturn(List.of(() -> REFERENCE));

        notificationService.deleteByReferenceNumbers(List.of(REFERENCE), AUDIT_CONTEXT);

        verify(notificationRepository).deleteAllByReferenceNumberIn(List.of(REFERENCE));
        ArgumentCaptor<Audit> auditCaptor = ArgumentCaptor.forClass(Audit.class);
        verify(auditRepository).save(auditCaptor.capture());
        Audit audit = auditCaptor.getValue();
        assertThat(audit.getResult()).isEqualTo(Result.SUCCESS);
        assertThat(audit.getAction()).isEqualTo(Action.DELETE_NOTIFICATIONS);
        assertThat(audit.getTraceId()).isEqualTo("trace-1");
        assertThat(audit.getUserId()).isEqualTo("user-1");
    }

    @Test
    void deleteByReferenceNumbers_shouldDeleteNothingAndAuditFailure_whenAnyReferenceIsMissing() {
        when(notificationRepository.findAllByReferenceNumberIn(List.of(REFERENCE, "26-MSSNG1")))
            .thenReturn(List.of(() -> REFERENCE));

        assertThatThrownBy(() -> notificationService.deleteByReferenceNumbers(
            List.of(REFERENCE, "26-MSSNG1"), AUDIT_CONTEXT))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("26-MSSNG1");

        verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
        ArgumentCaptor<Audit> auditCaptor = ArgumentCaptor.forClass(Audit.class);
        verify(auditRepository).save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getResult()).isEqualTo(Result.FAILURE);
    }

    @Test
    void deleteByReferenceNumbers_shouldBeANoOp_whenGivenAnEmptyList() {
        notificationService.deleteByReferenceNumbers(List.of(), AUDIT_CONTEXT);

        verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
        verify(auditRepository, never()).save(any());
    }

    @Test
    void deleteExpired_shouldDeleteTheDueBatchAndReturnItsSize() {
        when(notificationRepository.findExpired(any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of(() -> REFERENCE, () -> "26-DEF456"));

        int deleted = notificationService.deleteExpired(10);

        assertThat(deleted).isEqualTo(2);
        verify(notificationRepository)
            .deleteAllByReferenceNumberIn(List.of(REFERENCE, "26-DEF456"));
        verify(auditRepository, never()).save(any());
    }

    @Test
    void deleteExpired_shouldDeleteNothing_whenNoneAreDue() {
        when(notificationRepository.findExpired(any(Instant.class), any(PageRequest.class)))
            .thenReturn(List.of());

        int deleted = notificationService.deleteExpired(10);

        assertThat(deleted).isZero();
        verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
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
