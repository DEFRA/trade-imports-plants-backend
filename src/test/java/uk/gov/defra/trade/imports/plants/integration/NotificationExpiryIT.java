package uk.gov.defra.trade.imports.plants.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static uk.gov.defra.trade.imports.plants.integration.ExpireAtIndexes.assertSingleTtlIndexNamed;
import static uk.gov.defra.trade.imports.plants.integration.ExpireAtIndexes.expireAtIndexes;

import com.mongodb.client.MongoClient;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    properties = {
        "notification.ttl.expiry.enabled=true",
        "spring.data.mongodb.database=notification-expiry-it"
    })
class NotificationExpiryIT extends IntegrationBase {

  private static final String TTL_INDEX = "notification_expire_at_ttl";

  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private MongoClient mongoClient;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private NotificationExpiryIndex notificationExpiryIndex;

  @BeforeEach
  void deleteAllNotifications() {
    notificationRepository.deleteAll();
  }

  @BeforeEach
  void putTtlIndexInPlace() {
    notificationExpiryIndex.replacePlainExpireAtIndexWithTtlIndex();
  }

  @AfterEach
  void restoreTtlMonitorSleep() {
    setTtlMonitorSleepSecs(60);
  }

  @Test
  void replacePlainExpireAtIndexWithTtlIndex_shouldLeaveOnlyTheTtlIndex_whenAPlainIndexExists() {
    createPlainExpireAtIndex();

    notificationExpiryIndex.replacePlainExpireAtIndexWithTtlIndex();

    assertSingleTtlIndexNamed(mongoTemplate, TTL_INDEX);
  }

  @Test
  void replacePlainExpireAtIndexWithTtlIndex_shouldPutTheTtlIndexInPlace_whenAnotherInstanceDroppedThePlainIndexFirst() {
    createPlainExpireAtIndex();

    new NotificationExpiryIndex(
        templateOvertakenAfterListingIndexes(), new NotificationTtlConfig(null, "dev"))
        .replacePlainExpireAtIndexWithTtlIndex();

    assertSingleTtlIndexNamed(mongoTemplate, TTL_INDEX);
  }

  @Test
  void expiredNotifications_shouldBeRemovedByMongo_whileUndatedAndUnexpiredOnesStay() {
    String expiredReference = "26-EXP001";
    String undatedReference = "26-UND001";
    String unexpiredReference = "26-ACT001";
    saveNotification(expiredReference, Instant.now().minus(1, ChronoUnit.HOURS));
    saveNotification(undatedReference, null);
    saveNotification(unexpiredReference, Instant.now().plus(7, ChronoUnit.DAYS));

    setTtlMonitorSleepSecs(1);

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() ->
            assertThat(notificationRepository.findByReferenceNumber(expiredReference)).isEmpty());
    assertThat(notificationRepository.findByReferenceNumber(undatedReference)).isPresent();
    assertThat(notificationRepository.findByReferenceNumber(unexpiredReference)).isPresent();
  }

  private void createPlainExpireAtIndex() {
    IndexOperations notificationIndexes = mongoTemplate.indexOps(NotificationAggregate.class);
    expireAtIndexes(mongoTemplate)
        .forEach(index -> notificationIndexes.dropIndex(index.getString("name")));
    notificationIndexes.createIndex(
        new Index().on("expireAt", Sort.Direction.ASC).named("expireAt"));
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

  private void saveNotification(String referenceNumber, Instant expireAt) {
    notificationRepository.save(NotificationAggregate.builder()
        .referenceNumber(referenceNumber)
        .status(NotificationStatus.DRAFT)
        .expireAt(expireAt)
        .build());
  }

  private void setTtlMonitorSleepSecs(int seconds) {
    mongoClient
        .getDatabase("admin")
        .runCommand(new Document("setParameter", 1).append("ttlMonitorSleepSecs", seconds));
  }
}
