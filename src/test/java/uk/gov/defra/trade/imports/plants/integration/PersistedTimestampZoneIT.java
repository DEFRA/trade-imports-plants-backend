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

/**
 * The five persisted timestamps are stored as the instant they name, and a date-only property as
 * the {@code YYYY-MM-DD} string it names, whatever the JVM's default zone.
 *
 * <p>Every storage assertion here reads the <em>raw BSON</em> rather than round-tripping through
 * the repository. A round trip decodes with the same zone that encoded it, so it cancels any drift
 * out and passes whether the fields are {@code Instant} or {@code LocalDateTime} — it cannot tell
 * the two apart. The read-side tests deliberately go the other way, through the repository,
 * because what they pin is the decode rather than the encode.
 *
 * <p>The zone is pinned to {@code Europe/London} rather than left to the host: on a UTC CI
 * container the drift these tests guard against is zero, so the test would pass without proving
 * anything. {@code TimeZone.setDefault} is JVM-wide, hence the restore in {@link #restoreTimeZone()}.
 */
class PersistedTimestampZoneIT extends IntegrationBase {

    /** Mid-BST, when Europe/London is UTC+01:00 — the offset that would show up as drift. */
    private static final Instant CREATED = Instant.parse("2026-07-21T00:30:00Z");
    private static final Instant UPDATED = Instant.parse("2026-07-22T23:45:10.123456Z");
    private static final Instant SUBMITTED_AT = Instant.parse("2026-07-22T23:45:10Z");
    private static final Instant EXPIRE_AT = Instant.parse("2026-08-21T00:30:00Z");
    private static final Instant AUDIT_TIMESTAMP = Instant.parse("2026-07-23T00:15:00Z");

    /** A calendar date: the day the user chose, with no time and no zone. */
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
        // Captured here rather than in a field initializer: by now the Spring context has loaded
        // Application, which pins the JVM to UTC, so the restore puts that pin back and not the
        // host zone.
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
        // Given — the zone captured after the context loaded, before this class changed it
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
        // BSON dates are millisecond-precision, so the microseconds on UPDATED are truncated on
        // the way in. That is storage, not zone drift — the millisecond value is exact.
        assertThat(stored.get("updated", Date.class).toInstant())
            .isEqualTo(Instant.parse("2026-07-22T23:45:10.123Z"));
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

    /**
     * A document written before EUDPA-639 holds a plain BSON date in each of these fields, exactly
     * as one written after it does — the encoding did not change, only the Java type that reads
     * it. Written here as raw BSON rather than through the repository, because the repository can
     * no longer produce the old shape.
     */
    @Test
    void findByReferenceNumber_shouldReadBackEveryTimestamp_whenTheDocumentWasWrittenBeforeTheChange() {
        // Given — a document in the pre-change shape: BSON dates written by the old converters
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

    /**
     * The sort behind {@code ?sort=createdAt} orders on the stored BSON date. Driven through
     * {@link NotificationSort#toSort(String)} and the repository — the same pair the service
     * uses — rather than a raw driver sort, so it is the application's own ordering of an {@code
     * Instant} field that is pinned here, not MongoDB's.
     */
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

        // When — the frontend's own sort parameter, resolved the way the service resolves it
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

    /**
     * The expiry sweeper's query compares {@code expireAt} with the instant it is handed. A
     * notification is due once that instant reaches its {@code expireAt}; one with no
     * {@code expireAt} is never due.
     */
    @Test
    void findExpired_shouldSelectOnlyNotificationsWhoseExpireAtHasPassed_whenJvmDefaultZoneIsBst() {
        // Given — one past its expiry, one exactly at it, one 30 minutes short, one never expiring
        Instant now = EXPIRE_AT;
        saveExpiring("GBN-HRP-26-EXP001", now.minus(1, ChronoUnit.DAYS));
        saveExpiring("GBN-HRP-26-EXP002", now);
        // Inside the hour a BST JVM would be out by, so a zone-shifted comparison would select it.
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

    /**
     * Plants has no typed date-only field yet, so the {@code LocalDate} converter pair is proved
     * against {@link DateProbe}. Were the property stored as a BSON date,
     * {@code get(..., String.class)} would throw rather than pass.
     */
    @Test
    void save_shouldStoreACalendarDateAsAnIsoDateString_whenJvmDefaultZoneIsBst() {
        // Given / When — a date-only property saved under the BST zone pinned in setUp
        mongoTemplate.save(new DateProbe("probe-1", ARRIVAL_DATE));

        // Then
        Document stored = mongoTemplate.getCollection(DATE_PROBE_COLLECTION).find().first();
        assertThat(stored).isNotNull();
        assertThat(stored.get("arrivalDate", String.class)).isEqualTo("2026-07-21");
    }

    /**
     * A stored date-only property reads back as the same calendar date whatever zone the reading
     * JVM is in. The document is written under UTC and read under zones either side of it.
     */
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

    /**
     * The {@code String} to {@code LocalDate} reading converter is registered globally, so this
     * pins that it runs only where the target property is a {@code LocalDate}. Arrival date
     * travels inside the untyped {@code fulfilments} payload as a string, and must come back as
     * the string that was stored.
     */
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

    /** Stand-in for the first document that will carry a typed date-only property. */
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
