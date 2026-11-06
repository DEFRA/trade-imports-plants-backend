package uk.gov.defra.trade.imports.plants.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import com.mongodb.client.MongoClient;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.test.context.TestPropertySource;
import uk.gov.defra.trade.imports.plants.configuration.NotificationTtlConfig;
import uk.gov.defra.trade.imports.plants.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.plants.notification.NotificationExpiryIndex;
import uk.gov.defra.trade.imports.plants.notification.NotificationRepository;
import uk.gov.defra.trade.imports.plants.notification.NotificationStatus;

@TestPropertySource(
    properties = {"notification.ttl.days=7", "notification.ttl.expiry.enabled=true"})
class NotificationExpiryIT extends IntegrationBase {

  private static final String TTL_INDEX = "notification_expire_at_ttl";
  private static final Document EXPIRE_AT_KEY = new Document("expireAt", 1);

  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private MongoClient mongoClient;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private NotificationExpiryIndex notificationExpiryIndex;

  @BeforeEach
  void ensureTtlIndex() {
    notificationRepository.deleteAll();
    notificationExpiryIndex.replacePlainExpireAtIndexWithTtlIndex();
  }

  @AfterEach
  void restoreSharedMongo() {
    expireAtIndexes().forEach(
        index -> mongoTemplate.indexOps(NotificationAggregate.class)
            .dropIndex(index.getString("name")));
    setTtlMonitorSleepSecs(60);
  }

  @Test
  void ttlIndex_isOnExpireAtAndExpiresNotificationsAtThatTime() {
    // When
    List<Document> indexes = expireAtIndexes();

    // Then
    assertThat(indexes).singleElement().satisfies(index -> {
      assertThat(index.getString("name")).isEqualTo(TTL_INDEX);
      assertThat(index.get("expireAfterSeconds", Number.class).longValue()).isZero();
    });
  }

  @Test
  void replacePlainExpireAtIndexWithTtlIndex_replacesAPlainExpireAtIndexWithTheTtlIndex() {
    // Given
    mongoTemplate.indexOps(NotificationAggregate.class).dropIndex(TTL_INDEX);
    mongoTemplate.indexOps(NotificationAggregate.class)
        .createIndex(new Index().on("expireAt", Sort.Direction.ASC));

    // When
    notificationExpiryIndex.replacePlainExpireAtIndexWithTtlIndex();

    // Then
    assertThat(expireAtIndexes()).singleElement().satisfies(index -> {
      assertThat(index.getString("name")).isEqualTo(TTL_INDEX);
      assertThat(index.get("expireAfterSeconds", Number.class).longValue()).isZero();
    });
  }

  @Test
  void replacePlainExpireAtIndexWithTtlIndex_shouldPutTheTtlIndexInPlace_whenAnotherInstanceDroppedThePlainIndexFirst() {
    mongoTemplate.indexOps(NotificationAggregate.class).dropIndex(TTL_INDEX);
    mongoTemplate.indexOps(NotificationAggregate.class)
        .createIndex(new Index().on("expireAt", Sort.Direction.ASC).named("expireAt"));

    new NotificationExpiryIndex(
        templateOvertakenAfterListingIndexes(), new NotificationTtlConfig(null, "dev"))
        .replacePlainExpireAtIndexWithTtlIndex();

    assertThat(expireAtIndexes()).singleElement().satisfies(index -> {
      assertThat(index.getString("name")).isEqualTo(TTL_INDEX);
      assertThat(index.get("expireAfterSeconds", Number.class).longValue()).isZero();
    });
  }

  @Test
  void expiredNotifications_areRemovedByMongoWhileUndatedAndUnexpiredOnesStay() {
    // Given
    String expired = save("26-EXP001", Instant.now().minus(1, ChronoUnit.HOURS));
    String undated = save("26-UND001", null);
    String unexpired = save("26-ACT001", Instant.now().plus(7, ChronoUnit.DAYS));

    // When
    setTtlMonitorSleepSecs(1);

    // Then
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(
            () -> assertThat(notificationRepository.findByReferenceNumber(expired)).isEmpty());
    assertThat(notificationRepository.findByReferenceNumber(undated)).isPresent();
    assertThat(notificationRepository.findByReferenceNumber(unexpired)).isPresent();
  }

  private MongoTemplate templateOvertakenAfterListingIndexes() {
    IndexOperations notificationIndexes = mongoTemplate.indexOps(NotificationAggregate.class);
    IndexOperations overtakenIndexes =
        mock(IndexOperations.class, delegatesTo(notificationIndexes));
    doAnswer(listing -> {
      List<IndexInfo> listed = notificationIndexes.getIndexInfo();
      notificationIndexes.dropIndex("expireAt");
      return listed;
    }).when(overtakenIndexes).getIndexInfo();
    MongoTemplate overtakenTemplate = spy(mongoTemplate);
    doReturn(overtakenIndexes).when(overtakenTemplate).indexOps(NotificationAggregate.class);
    return overtakenTemplate;
  }

  private String save(String referenceNumber, Instant expireAt) {
    notificationRepository.save(NotificationAggregate.builder()
        .referenceNumber(referenceNumber)
        .status(NotificationStatus.DRAFT)
        .created(Instant.now().minus(8, ChronoUnit.DAYS))
        .expireAt(expireAt)
        .build());
    return referenceNumber;
  }

  private List<Document> expireAtIndexes() {
    return mongoTemplate.getCollection(mongoTemplate.getCollectionName(NotificationAggregate.class))
        .listIndexes().into(new ArrayList<>()).stream()
        .filter(index -> EXPIRE_AT_KEY.equals(index.get("key", Document.class)))
        .toList();
  }

  private void setTtlMonitorSleepSecs(int seconds) {
    mongoClient
        .getDatabase("admin")
        .runCommand(new Document("setParameter", 1).append("ttlMonitorSleepSecs", seconds));
  }
}
