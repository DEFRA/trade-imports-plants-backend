package uk.gov.defra.trade.imports.plants.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
import uk.gov.defra.trade.imports.plants.exceptions.NotFoundException;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

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
            .concurrencyToken(2L)
            .status(status)
            .created(LocalDateTime.now().minusDays(1))
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
        when(referenceNumberGenerator.generate()).thenReturn(REFERENCE);
        repositoryEchoesSaves();

        // When
        NotificationAggregate result = notificationService.saveNotification(
            NotificationDto.builder().fulfilments(List.of(new Document("k", "v"))).build());

        // Then
        assertThat(result.getReferenceNumber()).isEqualTo(REFERENCE);
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(result.getCreated()).isNotNull();
        assertThat(result.getFulfilments()).containsExactly(new Document("k", "v"));
    }

    @Test
    void saveNotification_shouldNotStampExpireAt_whenNoTtlConfigured() {
        // Given the prod-safe default: no TTL days configured
        when(referenceNumberGenerator.generate()).thenReturn(REFERENCE);
        repositoryEchoesSaves();

        // When
        NotificationAggregate result =
            notificationService.saveNotification(NotificationDto.builder().build());

        // Then
        assertThat(result.getExpireAt()).isNull();
    }

    @Test
    void saveNotification_shouldNotStampExpireAt_whenEnvironmentIsProd() {
        // Given TTL days set but the environment is prod — the second independent safeguard
        notificationService = newService(ttlConfig(7, "prod"));
        when(referenceNumberGenerator.generate()).thenReturn(REFERENCE);
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
        when(referenceNumberGenerator.generate()).thenReturn(REFERENCE);
        repositoryEchoesSaves();

        // When
        NotificationAggregate result =
            notificationService.saveNotification(NotificationDto.builder().build());

        // Then
        assertThat(result.getExpireAt()).isEqualTo(result.getCreated().plusDays(7));
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
            .hasMessageContaining("Cannot save notification with status: SUBMITTED");
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
        Notification clone = new Notification();
        when(notificationRepository.findByReferenceNumber(REFERENCE)).thenReturn(Optional.of(submitted));
        when(notificationContentMapper.deepClone(submitted.getNotification())).thenReturn(clone);
        repositoryEchoesSaves();

        // When
        NotificationAggregate result = notificationService.amendNotification(REFERENCE);

        // Then
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.AMEND);
        assertThat(result.getSubmittedNotificationBaseline()).isSameAs(clone);
        assertThat(result.getSubmittedFulfilmentsBaseline())
            .containsExactly(new Document("k", "submitted"));
    }

    @Test
    void amendNotification_shouldSnapshotFulfilmentsIndependently_soLaterEditsDoNotLeakIntoTheBaseline() {
        // Given
        NotificationAggregate submitted = stored(NotificationStatus.SUBMITTED);
        Document live = new Document("answers", new Document("fieldOne", "VALUE_ONE"));
        submitted.setFulfilments(List.of(live));
        when(notificationRepository.findByReferenceNumber(REFERENCE)).thenReturn(Optional.of(submitted));
        when(notificationContentMapper.deepClone(any())).thenReturn(new Notification());
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
        LocalDateTime originalSubmittedAt = LocalDateTime.now().minusDays(2);
        NotificationAggregate amending = stored(NotificationStatus.AMEND);
        amending.setSubmittedAt(originalSubmittedAt);
        amending.setFulfilments(List.of(new Document("k", "edited")));
        Notification baseline = new Notification();
        amending.setSubmittedNotificationBaseline(baseline);
        amending.setSubmittedFulfilmentsBaseline(List.of(new Document("k", "submitted")));
        Notification restored = new Notification();
        when(notificationRepository.findByReferenceNumber(REFERENCE)).thenReturn(Optional.of(amending));
        when(notificationContentMapper.deepClone(baseline)).thenReturn(restored);
        repositoryEchoesSaves();

        // When
        NotificationAggregate result = notificationService.cancelAmendNotification(REFERENCE);

        // Then
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(result.getNotification()).isSameAs(restored);
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
        when(referenceNumberGenerator.generate()).thenReturn("26-XYZ789");
        repositoryEchoesSaves();

        // When
        NotificationAggregate result = notificationService.copyNotification(REFERENCE, 2L);

        // Then
        assertThat(result.getReferenceNumber()).isEqualTo("26-XYZ789");
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

    @Test
    void deleteByReferenceNumbers_shouldDeleteAllAndAuditSuccess_whenEveryReferenceExists() {
        // Given
        when(notificationRepository.findAllByReferenceNumberIn(List.of(REFERENCE)))
            .thenReturn(List.of(() -> REFERENCE));

        // When
        notificationService.deleteByReferenceNumbers(List.of(REFERENCE), AUDIT_CONTEXT);

        // Then
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
        // Given only one of the two references exists
        when(notificationRepository.findAllByReferenceNumberIn(List.of(REFERENCE, "26-MSSNG1")))
            .thenReturn(List.of(() -> REFERENCE));

        // When / Then
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
        // When
        notificationService.deleteByReferenceNumbers(List.of(), AUDIT_CONTEXT);

        // Then
        verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
        verify(auditRepository, never()).save(any());
    }

    @Test
    void deleteExpired_shouldDeleteTheDueBatchAndReturnItsSize() {
        // Given
        when(notificationRepository.findExpired(any(LocalDateTime.class), any(PageRequest.class)))
            .thenReturn(List.of(() -> REFERENCE, () -> "26-DEF456"));

        // When
        int deleted = notificationService.deleteExpired(10);

        // Then
        assertThat(deleted).isEqualTo(2);
        verify(notificationRepository)
            .deleteAllByReferenceNumberIn(List.of(REFERENCE, "26-DEF456"));
        verify(auditRepository, never()).save(any());
    }

    @Test
    void deleteExpired_shouldDeleteNothing_whenNoneAreDue() {
        // Given
        when(notificationRepository.findExpired(any(LocalDateTime.class), any(PageRequest.class)))
            .thenReturn(List.of());

        // When
        int deleted = notificationService.deleteExpired(10);

        // Then
        assertThat(deleted).isZero();
        verify(notificationRepository, never()).deleteAllByReferenceNumberIn(anyList());
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
