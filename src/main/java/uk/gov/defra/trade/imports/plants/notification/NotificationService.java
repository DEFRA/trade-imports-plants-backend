package uk.gov.defra.trade.imports.plants.notification;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    /** Replaces the content of a DRAFT or AMEND notification, and refuses any other status. */
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
        return saveWithStatus(notificationAggregate, notificationAggregate.getStatus());
    }

    /** Creates a new DRAFT copied from an existing notification. */
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

    public NotificationFulfilmentsView findFulfilmentsView(String referenceNumber) {
        return notificationRepository.findFulfilmentsViewByReferenceNumber(referenceNumber)
            .orElseThrow(() -> new NotFoundException(
                CANNOT_FIND_NOTIFICATION_WITH_REFERENCE_NUMBER + referenceNumber));
    }

    public NotificationPageResponse findDashboardPage(int page, String sort, String referenceNumber) {
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

        applySubmission(notificationAggregate);
        return saveWithStatus(notificationAggregate, NotificationStatus.SUBMITTED);
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

        return saveWithStatus(notificationAggregate, NotificationStatus.AMEND);
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
        return saveWithStatus(notificationAggregate, NotificationStatus.SUBMITTED);
    }

    private NotificationAggregate saveWithStatus(NotificationAggregate notificationAggregate,
        NotificationStatus targetStatus) {
        notificationAggregate.setStatus(targetStatus);
        notificationAggregate.setUpdated(Instant.now());
        return notificationRepository.save(notificationAggregate);
    }

    private void applySubmission(NotificationAggregate notificationAggregate) {
        if (notificationAggregate.getStatus() == NotificationStatus.AMEND) {
            notificationAggregate.setSubmittedNotificationBaseline(null);
            notificationAggregate.setSubmittedFulfilmentsBaseline(null);
        }
        notificationAggregate.setSubmittedAt(Instant.now());
    }

    /** Marks a notification DELETED, and returns one already DELETED unchanged. */
    @Transactional
    public NotificationAggregate softDeleteNotification(String referenceNumber) {
        NotificationAggregate notificationAggregate = requireByReferenceNumber(referenceNumber);
        if (notificationAggregate.getStatus() == NotificationStatus.DELETED) {
            return notificationAggregate;
        }
        if (notificationAggregate.getStatus() != NotificationStatus.DRAFT
            && notificationAggregate.getStatus() != NotificationStatus.SUBMITTED
            && notificationAggregate.getStatus() != NotificationStatus.AMEND) {
            throw new BadRequestException(
                "Cannot delete notification with status: " + notificationAggregate.getStatus());
        }
        return saveWithStatus(notificationAggregate, NotificationStatus.DELETED);
    }

    public ReferenceNumberPageResponse findAllReferenceNumbers(int page) {
        log.debug("Fetching notification reference numbers page {} (size {})", page, adminPageSize);
        Page<NotificationReferenceOnly> result = notificationRepository.findAllReferencesBy(
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
            notificationRepository.findAllReferencesByReferenceNumberIn(referenceNumbers);
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

    private void stampExpiry(NotificationAggregate notificationAggregate) {
        Integer days = ttlConfig.days();
        if (days == null || ttlConfig.isProd()) {
            return;
        }
        notificationAggregate.setExpireAt(notificationAggregate.getCreated().plus(days, ChronoUnit.DAYS));
    }

    private NotificationAggregate createNotification(NotificationDto dto) {
        NotificationAggregate notificationAggregate = new NotificationAggregate();
        notificationAggregate.setCreated(Instant.now());
        notificationAggregate.setStatus(NotificationStatus.DRAFT);
        stampExpiry(notificationAggregate);
        setNotificationDetails(dto, notificationAggregate);
        for (int attempt = 1; attempt <= MAX_REF_RETRIES; attempt++) {
            notificationAggregate.setReferenceNumber(referenceNumberGenerator.generate());
            try {
                NotificationAggregate saved =
                    saveWithStatus(notificationAggregate, NotificationStatus.DRAFT);
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
        return saveWithStatus(existing, existing.getStatus());
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
        notificationAggregate.setUpdated(Instant.now());
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
            .timestamp(Instant.now())
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
