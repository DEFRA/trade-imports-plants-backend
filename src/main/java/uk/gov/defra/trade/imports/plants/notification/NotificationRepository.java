package uk.gov.defra.trade.imports.plants.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface NotificationRepository extends MongoRepository<NotificationAggregate, String> {

    boolean existsByReferenceNumber(String referenceNumber);

    Optional<NotificationAggregate> findByReferenceNumberAndOrganisationId(
        String referenceNumber, String organisationId);

    List<NotificationReferenceOnly> findAllByReferenceNumberIn(List<String> referenceNumbers);

    Optional<NotificationFulfilmentsView> findFulfilmentsViewByReferenceNumberAndOrganisationId(
        String referenceNumber, String organisationId);

    Optional<NotificationView> findViewByReferenceNumberAndStatusInAndOrganisationId(
        String referenceNumber, List<NotificationStatus> statuses, String organisationId);

    Page<NotificationView> findAllViewByStatusInAndOrganisationId(
        List<NotificationStatus> statuses, String organisationId, Pageable pageable);

    /** Notifications with a non-null {@code expireAt} at or before {@code now}. */
    @Query("{ 'expireAt': { $ne: null, $lte: ?0 } }")
    List<NotificationReferenceOnly> findExpired(Instant now, Pageable pageable);

    Page<NotificationReferenceOnly> findAllProjectedBy(Pageable pageable);

    void deleteAllByReferenceNumberIn(List<String> referenceNumbers);

}
