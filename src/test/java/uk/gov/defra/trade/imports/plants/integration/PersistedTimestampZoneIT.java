package uk.gov.defra.trade.imports.plants.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import uk.gov.defra.trade.imports.plants.audit.Action;
import uk.gov.defra.trade.imports.plants.audit.Audit;
import uk.gov.defra.trade.imports.plants.audit.AuditRepository;
import uk.gov.defra.trade.imports.plants.audit.Result;
import uk.gov.defra.trade.imports.plants.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.plants.notification.NotificationReferenceOnly;
import uk.gov.defra.trade.imports.plants.notification.NotificationRepository;
import uk.gov.defra.trade.imports.plants.notification.NotificationSort;
import uk.gov.defra.trade.imports.plants.notification.NotificationStatus;

class PersistedTimestampZoneIT extends IntegrationBase {

    private static final Instant CREATED = Instant.parse("2026-07-21T00:30:00Z");
    private static final Instant UPDATED = Instant.parse("2026-07-22T23:45:10.123456Z");
    private static final Instant UPDATED_AT_BSON_MILLISECOND_PRECISION = UPDATED.truncatedTo(ChronoUnit.MILLIS);
    private static final Instant SUBMITTED_AT = Instant.parse("2026-07-22T23:45:10Z");
    private static final Instant EXPIRE_AT = Instant.parse("2026-08-21T00:30:00Z");
    private static final Instant AUDIT_TIMESTAMP = Instant.parse("2026-07-23T00:15:00Z");

    private static final LocalDate ARRIVAL_DATE = LocalDate.parse("2026-07-21");

    private static final String REF = "GBN-HRP-26-TZ0001";
    private static final String NOTIFICATION_COLLECTION = "notification";
    private static final String DATE_PROBE_COLLECTION = "dateProbe";

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private MongoTemplate mongoTemplate;

    private TimeZone originalTimeZone;

    @BeforeEach
    void setUp() {
        originalTimeZone = TimeZone.getDefault();
        notificationRepository.deleteAll();
        auditRepository.deleteAll();
        mongoTemplate.dropCollection(DATE_PROBE_COLLECTION);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"));
    }

    @AfterEach
    void restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    void application_shouldPinTheJvmDefaultZoneToUtc() {
        // When / Then
        assertThat(originalTimeZone.getID()).isEqualTo("UTC");
    }

    @Test
    void save_shouldStoreNotificationTimestampsAsTheInstantTheyName_whenJvmDefaultZoneIsBst() {
        // Given — an aggregate carrying all four top-level timestamps
        NotificationAggregate aggregate = NotificationAggregate.builder()
            .referenceNumber(REF)
            .status(NotificationStatus.SUBMITTED)
            .created(CREATED)
            .updated(UPDATED)
            .submittedAt(SUBMITTED_AT)
            .expireAt(EXPIRE_AT)
            .build();

        // When
        notificationRepository.save(aggregate);

        // Then
        Document stored = storedNotification();
        assertThat(stored).isNotNull();
        assertThat(stored.get("created", Date.class).toInstant()).isEqualTo(CREATED);
        assertThat(stored.get("submittedAt", Date.class).toInstant()).isEqualTo(SUBMITTED_AT);
        assertThat(stored.get("expireAt", Date.class).toInstant()).isEqualTo(EXPIRE_AT);
        assertThat(stored.get("updated", Date.class).toInstant())
            .isEqualTo(UPDATED_AT_BSON_MILLISECOND_PRECISION);
    }

    @Test
    void save_shouldStoreTheAuditTimestampAsTheInstantItNames_whenJvmDefaultZoneIsBst() {
        // Given — an audit row stamped mid-BST
        Audit audit = Audit.builder()
            .action(Action.DELETE_NOTIFICATIONS)
            .result(Result.SUCCESS)
            .timestamp(AUDIT_TIMESTAMP)
            .numberOfNotifications(1)
            .build();

        // When
        auditRepository.save(audit);

        // Then
        Document stored = mongoTemplate.getCollection("audit").find().first();
        assertThat(stored).isNotNull();
        assertThat(stored.get("timestamp", Date.class).toInstant()).isEqualTo(AUDIT_TIMESTAMP);
    }

    @Test
    void findByReferenceNumber_shouldReadBackEveryTimestamp_whenTheDocumentHoldsRawBsonDates() {
        // Given
        mongoTemplate.getCollection(NOTIFICATION_COLLECTION).insertOne(new Document()
            .append("referenceNumber", REF)
            .append("status", NotificationStatus.SUBMITTED.name())
            .append("created", Date.from(CREATED))
            .append("updated", Date.from(SUBMITTED_AT))
            .append("submittedAt", Date.from(SUBMITTED_AT))
            .append("expireAt", Date.from(EXPIRE_AT)));

        // When
        NotificationAggregate read = notificationRepository.findByReferenceNumber(REF).orElseThrow();

        // Then — every field comes back as the instant the stored BSON date names
        assertThat(read.getCreated()).isEqualTo(CREATED);
        assertThat(read.getUpdated()).isEqualTo(SUBMITTED_AT);
        assertThat(read.getSubmittedAt()).isEqualTo(SUBMITTED_AT);
        assertThat(read.getExpireAt()).isEqualTo(EXPIRE_AT);
    }

    @Test
    void findAll_shouldOrderByTheStoredInstant_whenSortedByCreatedAt() {
        // Given — two notifications saved newest-first, so insertion order cannot flatter the sort
        notificationRepository.save(NotificationAggregate.builder()
            .referenceNumber("GBN-HRP-26-TZ0002")
            .status(NotificationStatus.DRAFT)
            .created(Instant.parse("2026-01-02T10:00:00Z"))
            .build());
        notificationRepository.save(NotificationAggregate.builder()
            .referenceNumber("GBN-HRP-26-TZ0003")
            .status(NotificationStatus.DRAFT)
            .created(Instant.parse("2026-01-01T10:00:00Z"))
            .build());

        // When
        var ascending = notificationRepository.findAll(
            PageRequest.of(0, 10, NotificationSort.toSort("createdAt,asc")));
        var descending = notificationRepository.findAll(
            PageRequest.of(0, 10, NotificationSort.toSort("createdAt,desc")));

        // Then
        assertThat(ascending.getContent())
            .extracting(NotificationAggregate::getReferenceNumber)
            .containsExactly("GBN-HRP-26-TZ0003", "GBN-HRP-26-TZ0002");
        assertThat(descending.getContent())
            .extracting(NotificationAggregate::getReferenceNumber)
            .containsExactly("GBN-HRP-26-TZ0002", "GBN-HRP-26-TZ0003");
    }

    @Test
    void findExpired_shouldSelectOnlyNotificationsWhoseExpireAtHasPassed_whenJvmDefaultZoneIsBst() {
        // Given — one past its expiry, one exactly at it, one 30 minutes short, one never expiring
        Instant now = EXPIRE_AT;
        saveExpiring("GBN-HRP-26-EXP001", now.minus(1, ChronoUnit.DAYS));
        saveExpiring("GBN-HRP-26-EXP002", now);
        saveExpiring("GBN-HRP-26-EXP003", now.plus(30, ChronoUnit.MINUTES));
        saveExpiring("GBN-HRP-26-EXP004", null);

        // When
        List<NotificationReferenceOnly> due =
            notificationRepository.findExpired(now, PageRequest.of(0, 10));

        // Then
        assertThat(due)
            .extracting(NotificationReferenceOnly::getReferenceNumber)
            .containsExactlyInAnyOrder("GBN-HRP-26-EXP001", "GBN-HRP-26-EXP002");
    }

    @Test
    void save_shouldStoreACalendarDateAsAnIsoDateString_whenJvmDefaultZoneIsBst() {
        // Given / When — a date-only property saved under the BST zone pinned in setUp
        mongoTemplate.save(new DateProbe("probe-1", ARRIVAL_DATE));

        // Then
        Document stored = mongoTemplate.getCollection(DATE_PROBE_COLLECTION).find().first();
        assertThat(stored).isNotNull();
        assertThat(stored.get("arrivalDate", String.class)).isEqualTo("2026-07-21");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/London", "America/New_York", "Australia/Sydney"})
    void findById_shouldReadBackTheSameCalendarDate_whateverTheJvmDefaultZone(String zoneId) {
        // Given — a document written by a UTC JVM
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        mongoTemplate.save(new DateProbe("probe-1", ARRIVAL_DATE));

        // When — it is read by a JVM in another zone
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
        DateProbe read = mongoTemplate.findById("probe-1", DateProbe.class);

        // Then
        assertThat(read).isNotNull();
        assertThat(read.getArrivalDate()).isEqualTo(ARRIVAL_DATE);
    }

    @Test
    void read_shouldLeaveADateShapedStringInFulfilmentsAsAString() {
        // Given
        String dateShaped = "2026-07-21";
        notificationRepository.save(NotificationAggregate.builder()
            .referenceNumber(REF)
            .status(NotificationStatus.DRAFT)
            .created(CREATED)
            .fulfilments(List.of(new Document("arrivalDate", dateShaped)))
            .build());

        // When
        NotificationAggregate read = notificationRepository.findByReferenceNumber(REF).orElseThrow();

        // Then
        assertThat(read.getFulfilments().getFirst()).containsEntry("arrivalDate", dateShaped);
        assertThat(storedNotification().getList("fulfilments", Document.class).getFirst()
            .get("arrivalDate", String.class)).isEqualTo(dateShaped);
    }

    private void saveExpiring(String referenceNumber, Instant expireAt) {
        notificationRepository.save(NotificationAggregate.builder()
            .referenceNumber(referenceNumber)
            .status(NotificationStatus.DRAFT)
            .created(CREATED)
            .expireAt(expireAt)
            .build());
    }

    private Document storedNotification() {
        return mongoTemplate.getCollection(NOTIFICATION_COLLECTION)
            .find(new Document("referenceNumber", REF))
            .first();
    }

    @org.springframework.data.mongodb.core.mapping.Document(collection = DATE_PROBE_COLLECTION)
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class DateProbe {

        @Id
        private String id;

        private LocalDate arrivalDate;
    }
}
