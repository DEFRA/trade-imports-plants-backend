package uk.gov.defra.trade.imports.plants.notification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.defra.trade.imports.plants.audit.Action;
import uk.gov.defra.trade.imports.plants.audit.Audit;
import uk.gov.defra.trade.imports.plants.audit.AuditRepository;
import uk.gov.defra.trade.imports.plants.audit.Result;
import uk.gov.defra.trade.imports.plants.configuration.NotificationTtlConfig;
import uk.gov.defra.trade.imports.plants.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.plants.exceptions.NotFoundException;

/**
 * Owns the notification lifecycle: the DRAFT / SUBMITTED / AMEND / DELETED status machine,
 * optimistic concurrency on {@code concurrencyToken}, reference-number generation with retry,
 * amend baselines, non-prod expiry stamping and the admin delete paths.
 */
@Service
@Slf4j
public class NotificationService {

    private static final String CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER =
        "Cannot find notification with reference number: ";
    private static final int MAX_REF_RETRIES = 3;

    private final NotificationRepository notificationRepository;
    private final AuditRepository auditRepository;
    private final NotificationCopyMapper notificationCopyMapper;
    private final NotificationContentMapper notificationContentMapper;
    private final ReferenceNumberGenerator referenceNumberGenerator;
    private final NotificationTtlConfig ttlConfig;
    private final int listPageSize;
    private final int adminPageSize;

    public NotificationService(
        NotificationRepository notificationRepository,
        AuditRepository auditRepository,
        NotificationCopyMapper notificationCopyMapper,
        NotificationContentMapper notificationContentMapper,
        ReferenceNumberGenerator referenceNumberGenerator,
        NotificationTtlConfig ttlConfig,
        @Value("${notification.list.page-size}") int listPageSize,
        @Value("${notification.admin.page-size}") int adminPageSize) {
        this.notificationRepository = notificationRepository;
        this.auditRepository = auditRepository;
        this.notificationCopyMapper = notificationCopyMapper;
        this.notificationContentMapper = notificationContentMapper;
        this.referenceNumberGenerator = referenceNumberGenerator;
        this.ttlConfig = ttlConfig;
        this.listPageSize = listPageSize;
        this.adminPageSize = adminPageSize;
    }

    /** Creates a notification when the DTO carries no reference number, otherwise updates that one. */
    public NotificationAggregate saveNotification(NotificationDto notificationDto) {
        String referenceNumber = notificationDto.getReferenceNumber();
        if (referenceNumber == null || referenceNumber.isBlank()) {
            return createNotification(notificationDto);
        }
        return updateNotification(notificationDto);
    }

    /**
     * Replace the notification content at the given reference. Backs {@code PUT /notifications/{ref}}.
     * Requires DRAFT or AMEND — the state-transition entrypoints (submit / amend / cancelAmend /
     * softDelete) handle other cases.
     */
    @Transactional
    public NotificationAggregate replace(String referenceNumber, NotificationDto dto) {
        NotificationAggregate notificationAggregate = requireByReferenceNumber(referenceNumber);
        if (notificationAggregate.getStatus() != NotificationStatus.DRAFT
            && notificationAggregate.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException(
                "Cannot replace notification content with status: " + notificationAggregate.getStatus());
        }
        if (dto.getConcurrencyToken() == null) {
            throw new BadRequestException("concurrencyToken is required to replace a notification");
        }
        notificationAggregate.setConcurrencyToken(dto.getConcurrencyToken());
        setNotificationDetails(dto, notificationAggregate);
        return write(notificationAggregate, notificationAggregate.getStatus(), false);
    }

    /** Creates a new DRAFT copied from an existing notification. Backs {@code POST /notifications/{ref}/copy}. */
    @Transactional
    public NotificationAggregate copyNotification(String referenceNumber, Long expectedConcurrencyToken) {
        NotificationAggregate source = requireByReferenceNumber(referenceNumber);
        if (source.getStatus() != NotificationStatus.DRAFT
            && source.getStatus() != NotificationStatus.SUBMITTED
            && source.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException("Cannot copy notification with status: " + source.getStatus());
        }
        if (expectedConcurrencyToken == null || source.getConcurrencyToken() == null) {
            throw new IllegalStateException(
                "Cannot check copy source concurrencyToken for " + referenceNumber
                    + ": expectedConcurrencyToken=" + expectedConcurrencyToken
                    + ", source.concurrencyToken=" + source.getConcurrencyToken());
        }
        if (!source.getConcurrencyToken().equals(expectedConcurrencyToken)) {
            throw new OptimisticLockingFailureException(
                "Copy source " + referenceNumber + " has advanced from expected concurrencyToken "
                    + expectedConcurrencyToken + " to " + source.getConcurrencyToken());
        }
        log.info("Copying notification {}", referenceNumber);
        return createNotification(notificationCopyMapper.toCopyDto(source));
    }

    public NotificationPageResponse findAll(int page, String sort) {
        return findAll(page, sort, null);
    }

    /** Serves {@code GET /notifications/{ref}/fulfilments} — the frontend engine's rehydrate read. */
    public NotificationFulfilmentsView findFulfilmentsView(String referenceNumber) {
        return notificationRepository.findFulfilmentsViewByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
    }

    /** Serves {@code GET /notifications?…} for the dashboard list. */
    public NotificationPageResponse findAll(int page, String sort, String referenceNumber) {
        List<NotificationStatus> dashboardStatuses = List.of(
            NotificationStatus.DRAFT, NotificationStatus.SUBMITTED, NotificationStatus.AMEND);
        var pageable = PageRequest.of(page - 1, listPageSize, NotificationSort.toSort(sort));

        String trimmedReference = trimToNull(referenceNumber);
        if (trimmedReference != null) {
            log.debug("Fetching notification by reference {} for dashboard", trimmedReference);
            Page<NotificationView> matched = notificationRepository
                .findViewByReferenceNumberAndStatusIn(trimmedReference, dashboardStatuses)
                .<Page<NotificationView>>map(notification ->
                    new PageImpl<>(List.of(notification), pageable, 1))
                .orElseGet(() -> Page.empty(pageable));
            log.debug("Found {} notifications for reference {}", matched.getNumberOfElements(),
                trimmedReference);
            return NotificationPageResponse.from(matched);
        }

        log.debug("Fetching notifications page {} (size {}) with sort {}", page, listPageSize, sort);
        Page<NotificationView> result = notificationRepository.findAllViewByStatusIn(
            dashboardStatuses, pageable);
        log.debug("Found {} notifications on page {} of {}",
            result.getNumberOfElements(), result.getNumber() + 1, result.getTotalPages());
        return NotificationPageResponse.from(result);
    }

    /** DRAFT or AMEND to SUBMITTED. A re-submission from AMEND clears the amend baselines. */
    @Transactional
    public NotificationAggregate submitNotification(String referenceNumber) {
        NotificationAggregate notificationAggregate = requireByReferenceNumber(referenceNumber);

        if (notificationAggregate.getStatus() != NotificationStatus.DRAFT
            && notificationAggregate.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException(
                "Cannot submit notification with status: " + notificationAggregate.getStatus());
        }

        return write(notificationAggregate, NotificationStatus.SUBMITTED, true);
    }

    /** SUBMITTED to AMEND, snapshotting the current content and fulfilments as the restore point. */
    @Transactional
    public NotificationAggregate amendNotification(String referenceNumber) {
        NotificationAggregate notificationAggregate = requireByReferenceNumber(referenceNumber);

        if (notificationAggregate.getStatus() != NotificationStatus.SUBMITTED) {
            throw new BadRequestException(
                "Cannot amend notification with status: " + notificationAggregate.getStatus());
        }

        notificationAggregate.setSubmittedNotificationBaseline(
            notificationContentMapper.deepClone(notificationAggregate.getNotification()));
        notificationAggregate.setSubmittedFulfilmentsBaseline(
            Fulfilments.deepCopy(notificationAggregate.getFulfilments()));

        return write(notificationAggregate, NotificationStatus.AMEND, false);
    }

    /** AMEND back to SUBMITTED, restoring the snapshot taken when the amendment started. */
    @Transactional
    public NotificationAggregate cancelAmendNotification(String referenceNumber) {
        NotificationAggregate notificationAggregate = requireByReferenceNumber(referenceNumber);

        if (notificationAggregate.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException(
                "Cannot cancel amendment for notification with status: " + notificationAggregate.getStatus());
        }
        if (notificationAggregate.getSubmittedNotificationBaseline() == null) {
            throw new BadRequestException(
                "Cannot cancel amendment: no submitted baseline stored for notification");
        }

        notificationAggregate.setNotification(
            notificationContentMapper.deepClone(notificationAggregate.getSubmittedNotificationBaseline()));
        notificationAggregate.setSubmittedNotificationBaseline(null);
        notificationAggregate.setFulfilments(
            Fulfilments.deepCopy(notificationAggregate.getSubmittedFulfilmentsBaseline()));
        notificationAggregate.setSubmittedFulfilmentsBaseline(null);
        // submittedAt is deliberately NOT reset — reverting to the previously-submitted state
        // preserves the original submission timestamp.
        return write(notificationAggregate, NotificationStatus.SUBMITTED, false);
    }

    /**
     * Applies a status transition and persists. Submitting out of AMEND discards the amend
     * baselines; only an actual submission stamps {@code submittedAt}, so a cancel-amend that
     * lands back on SUBMITTED keeps the original submission timestamp.
     *
     * @param notification   the aggregate to persist
     * @param targetStatus   the status to move to
     * @param isSubmission   whether this write is a submission (and so mints a new {@code submittedAt})
     * @return the persisted aggregate, with its incremented concurrency token
     */
    private NotificationAggregate write(NotificationAggregate notification,
        NotificationStatus targetStatus, boolean isSubmission) {
        if (targetStatus == NotificationStatus.SUBMITTED
            && notification.getStatus() == NotificationStatus.AMEND) {
            notification.setSubmittedNotificationBaseline(null);
            notification.setSubmittedFulfilmentsBaseline(null);
        }
        notification.setStatus(targetStatus);
        notification.setUpdated(LocalDateTime.now());
        if (isSubmission) {
            notification.setSubmittedAt(LocalDateTime.now());
        }
        return notificationRepository.save(notification);
    }

    /** Marks a notification DELETED. Idempotent per REST DELETE convention. */
    @Transactional
    public NotificationAggregate softDeleteNotification(String referenceNumber) {
        NotificationAggregate notificationAggregate = requireByReferenceNumber(referenceNumber);
        // Idempotent per REST DELETE convention — a repeat call after a lost response is a no-op.
        if (notificationAggregate.getStatus() == NotificationStatus.DELETED) {
            return notificationAggregate;
        }
        if (notificationAggregate.getStatus() != NotificationStatus.DRAFT
            && notificationAggregate.getStatus() != NotificationStatus.SUBMITTED
            && notificationAggregate.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException(
                "Cannot delete notification with status: " + notificationAggregate.getStatus());
        }
        return write(notificationAggregate, NotificationStatus.DELETED, false);
    }

    public ReferenceNumberPageResponse findAllReferenceNumbers(int page) {
        log.debug("Fetching notification reference numbers page {} (size {})", page, adminPageSize);
        Page<NotificationReferenceOnly> result = notificationRepository.findAllProjectedBy(
            PageRequest.of(page, adminPageSize, Sort.by(Direction.DESC, "created")));
        log.debug("Found {} reference numbers on page {} of {}",
            result.getNumberOfElements(), result.getNumber() + 1, result.getTotalPages());
        return ReferenceNumberPageResponse.from(result);
    }

    /**
     * Hard-deletes the named notifications, writing an audit record either way. Every reference
     * must exist — a single miss deletes nothing and raises {@link NotFoundException}.
     *
     * @param referenceNumbers the notifications to delete; a null or empty list is a no-op
     * @param auditContext     who asked, for the audit record
     * @throws NotFoundException if any reference number does not exist
     */
    @Transactional(noRollbackFor = NotFoundException.class)
    public void deleteByReferenceNumbers(List<String> referenceNumbers, AuditContext auditContext) {
        if (referenceNumbers == null || referenceNumbers.isEmpty()) {
            return;
        }
        List<NotificationReferenceOnly> found =
            notificationRepository.findAllByReferenceNumberIn(referenceNumbers);
        Set<String> foundRefs = found.stream()
            .map(NotificationReferenceOnly::getReferenceNumber)
            .collect(Collectors.toSet());
        List<String> missing = referenceNumbers.stream()
            .filter(ref -> !foundRefs.contains(ref))
            .toList();
        if (!missing.isEmpty()) {
            createNotificationAuditRecord(referenceNumbers, auditContext, Result.FAILURE);
            throw new NotFoundException(
                "Cannot find notifications with reference numbers: " + String.join(", ", missing));
        }
        log.info("Deleting {} notifications", found.size());
        notificationRepository.deleteAllByReferenceNumberIn(referenceNumbers);
        createNotificationAuditRecord(referenceNumbers, auditContext, Result.SUCCESS);
    }

    /**
     * Deletes notifications whose {@code expireAt} has passed, up to {@code batchSize} per call.
     * Called by the non-prod {@code NotificationExpirySweeper}; unlike
     * {@link #deleteByReferenceNumbers} it writes no audit record. Notifications with a
     * {@code null} {@code expireAt} are never selected.
     *
     * @param batchSize maximum number of notifications to remove in this run
     * @return the number of notifications deleted
     */
    @Transactional
    public int deleteExpired(int batchSize) {
        List<NotificationReferenceOnly> due =
            notificationRepository.findExpired(LocalDateTime.now(), PageRequest.of(0, batchSize));
        if (due.isEmpty()) {
            return 0;
        }
        List<String> referenceNumbers = due.stream()
            .map(NotificationReferenceOnly::getReferenceNumber)
            .toList();
        log.info("Expiring {} notification(s)", referenceNumbers.size());
        notificationRepository.deleteAllByReferenceNumberIn(referenceNumbers);
        return referenceNumbers.size();
    }

    /**
     * Stamps {@code expireAt} on a freshly-created notification, but only when both prod safeguards
     * pass: a TTL duration is configured (non-prod config) and the running environment is not prod.
     * Anchored to {@code created}, so a notification expires a fixed window after creation
     * regardless of later activity.
     */
    private void stampExpiry(NotificationAggregate notificationAggregate) {
        Integer days = ttlConfig.days();
        if (days == null || ttlConfig.isProd()) {
            return;
        }
        notificationAggregate.setExpireAt(notificationAggregate.getCreated().plusDays(days));
    }

    private NotificationAggregate createNotification(NotificationDto dto) {
        NotificationAggregate notificationAggregate = new NotificationAggregate();
        notificationAggregate.setCreated(LocalDateTime.now());
        notificationAggregate.setStatus(NotificationStatus.DRAFT);
        stampExpiry(notificationAggregate);
        setNotificationDetails(dto, notificationAggregate);
        for (int attempt = 1; attempt <= MAX_REF_RETRIES; attempt++) {
            notificationAggregate.setReferenceNumber(referenceNumberGenerator.generate());
            try {
                NotificationAggregate saved =
                    write(notificationAggregate, NotificationStatus.DRAFT, false);
                log.info("Notification saved with reference number: {}", saved.getReferenceNumber());
                return saved;
            } catch (DuplicateKeyException _) {
                log.warn("Reference number collision on persistence attempt {}/{}; retrying",
                    attempt, MAX_REF_RETRIES);
            }
        }
        throw new IllegalStateException(
            "Failed to generate a unique reference number after " + MAX_REF_RETRIES + " attempts");
    }

    private NotificationAggregate updateNotification(NotificationDto dto) {
        String referenceNumber = dto.getReferenceNumber();
        NotificationAggregate existing = requireByReferenceNumber(referenceNumber);
        if (existing.getStatus() != NotificationStatus.DRAFT
            && existing.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException(
                "Cannot save notification with status: " + existing.getStatus());
        }
        if (dto.getConcurrencyToken() == null) {
            throw new BadRequestException("concurrencyToken is required to update a notification");
        }
        existing.setConcurrencyToken(dto.getConcurrencyToken());
        log.info("Updating notification {}", referenceNumber);
        setNotificationDetails(dto, existing);
        return write(existing, existing.getStatus(), false);
    }

    private NotificationAggregate requireByReferenceNumber(String referenceNumber) {
        return notificationRepository.findByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
    }

    private void setNotificationDetails(NotificationDto dto, NotificationAggregate notificationAggregate) {
        if (notificationAggregate.getNotification() == null) {
            notificationAggregate.setNotification(new Notification());
        }
        notificationAggregate.setFulfilments(dto.getFulfilments());
        notificationAggregate.setUpdated(LocalDateTime.now());
    }

    private void createNotificationAuditRecord(
        List<String> referenceNumbers, AuditContext auditContext, Result result) {
        Audit auditRecord = Audit.builder()
            .action(Action.DELETE_NOTIFICATIONS)
            .result(result)
            .notificationReferenceNumbers(referenceNumbers)
            .numberOfNotifications(referenceNumbers.size())
            .traceId(auditContext.traceId())
            .userId(auditContext.userId())
            .timestamp(LocalDateTime.now())
            .build();

        auditRepository.save(auditRecord);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
