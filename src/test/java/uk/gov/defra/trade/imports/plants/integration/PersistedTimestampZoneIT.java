package uk.gov.defra.trade.imports.plants.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
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
import uk.gov.defra.trade.imports.plants.notification.NotificationRepository;
import uk.gov.defra.trade.imports.plants.testsupport.TraderOrganisationTestSupport;
import uk.gov.defra.trade.imports.plants.notification.NotificationSort;
import uk.gov.defra.trade.imports.plants.notification.NotificationStatus;

class PersistedTimestampZoneIT extends IntegrationBase {

    private static final Instant CREATED = Instant.parse("2026-07-21T00:30:00Z");
    private static final Instant UPDATED_AT_BSON_MILLISECOND_PRECISION = Instant.parse("2026-07-22T23:45:10.123Z");
    private static final Instant SUBMITTED_AT = Instant.parse("2026-07-22T23:45:10Z");
    private static final Instant EXPIRE_AT = Instant.parse("2026-08-21T00:30:00Z");

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
        assertThat(originalTimeZone.getID()).isEqualTo("UTC");
    }

    @Test
    void save_shouldStoreNotificationTimestampsAsTheInstantTheyName_whenJvmDefaultZoneIsBst() {
        NotificationAggregate aggregate = NotificationAggregate.builder()
            .referenceNumber(REF)
            .status(NotificationStatus.SUBMITTED)
            .created(CREATED)
            .updated(Instant.parse("2026-07-22T23:45:10.123456Z"))
            .submittedAt(SUBMITTED_AT)
            .expireAt(EXPIRE_AT)
            .build();

        notificationRepository.save(aggregate);

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
        Instant auditTimestamp = Instant.parse("2026-07-23T00:15:00Z");
        Audit audit = Audit.builder()
            .action(Action.DELETE_NOTIFICATIONS)
            .result(Result.SUCCESS)
            .timestamp(auditTimestamp)
            .numberOfNotifications(1)
            .build();

        auditRepository.save(audit);

        Document stored = mongoTemplate.getCollection("audit").find().first();
        assertThat(stored).isNotNull();
        assertThat(stored.get("timestamp", Date.class).toInstant()).isEqualTo(auditTimestamp);
    }

    @Test
    void findByReferenceNumber_shouldReadBackEveryTimestamp_whenTheDocumentHoldsRawBsonDates() {
        mongoTemplate.getCollection(NOTIFICATION_COLLECTION).insertOne(new Document()
            .append("referenceNumber", REF)
            .append("organisationId", TraderOrganisationTestSupport.ORGANISATION_ID)
            .append("status", NotificationStatus.SUBMITTED.name())
            .append("created", Date.from(CREATED))
            .append("updated", Date.from(UPDATED_AT_BSON_MILLISECOND_PRECISION))
            .append("submittedAt", Date.from(SUBMITTED_AT))
            .append("expireAt", Date.from(EXPIRE_AT)));

        NotificationAggregate read = notificationRepository
            .findByReferenceNumberAndOrganisationId(REF, TraderOrganisationTestSupport.ORGANISATION_ID)
            .orElseThrow();

        assertThat(read.getCreated()).isEqualTo(CREATED);
        assertThat(read.getUpdated()).isEqualTo(UPDATED_AT_BSON_MILLISECOND_PRECISION);
        assertThat(read.getSubmittedAt()).isEqualTo(SUBMITTED_AT);
        assertThat(read.getExpireAt()).isEqualTo(EXPIRE_AT);
    }

    @Test
    void findAll_shouldOrderByTheStoredInstant_whenSortedByCreatedAt() {
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

        var ascending = notificationRepository.findAll(
            PageRequest.of(0, 10, NotificationSort.toSort("createdAt,asc")));
        var descending = notificationRepository.findAll(
            PageRequest.of(0, 10, NotificationSort.toSort("createdAt,desc")));

        assertThat(ascending.getContent())
            .extracting(NotificationAggregate::getReferenceNumber)
            .containsExactly("GBN-HRP-26-TZ0003", "GBN-HRP-26-TZ0002");
        assertThat(descending.getContent())
            .extracting(NotificationAggregate::getReferenceNumber)
            .containsExactly("GBN-HRP-26-TZ0002", "GBN-HRP-26-TZ0003");
    }

    @Test
    void save_shouldStoreACalendarDateAsAnIsoDateString_whenJvmDefaultZoneIsBst() {
        mongoTemplate.save(new DateProbe("probe-1", ARRIVAL_DATE));

        Document stored = mongoTemplate.getCollection(DATE_PROBE_COLLECTION).find().first();
        assertThat(stored).isNotNull();
        assertThat(stored.get("arrivalDate", String.class)).isEqualTo("2026-07-21");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/London", "America/New_York", "Australia/Sydney"})
    void findById_shouldReadBackTheSameCalendarDate_whateverTheJvmDefaultZone(String zoneId) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        mongoTemplate.save(new DateProbe("probe-1", ARRIVAL_DATE));

        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));
        DateProbe read = mongoTemplate.findById("probe-1", DateProbe.class);

        assertThat(read).isNotNull();
        assertThat(read.getArrivalDate()).isEqualTo(ARRIVAL_DATE);
    }

    @Test
    void read_shouldLeaveADateShapedStringInFulfilmentsAsAString() {
        String dateShaped = "2026-07-21";
        notificationRepository.save(NotificationAggregate.builder()
            .referenceNumber(REF)
            .organisationId(TraderOrganisationTestSupport.ORGANISATION_ID)
            .status(NotificationStatus.DRAFT)
            .created(CREATED)
            .fulfilments(List.of(new Document("arrivalDate", dateShaped)))
            .build());

        NotificationAggregate read = notificationRepository
            .findByReferenceNumberAndOrganisationId(REF, TraderOrganisationTestSupport.ORGANISATION_ID)
            .orElseThrow();

        assertThat(read.getFulfilments().getFirst()).containsEntry("arrivalDate", dateShaped);
        assertThat(storedNotification().getList("fulfilments", Document.class).getFirst()
            .get("arrivalDate", String.class)).isEqualTo(dateShaped);
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
