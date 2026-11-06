package uk.gov.defra.trade.imports.plants.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;
import uk.gov.defra.trade.imports.plants.notification.NotificationAggregate;

@TestPropertySource(properties = "spring.data.mongodb.database=notification-expiry-disabled-it")
class NotificationExpiryDisabledIT extends IntegrationBase {

  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void startup_shouldLeaveNotificationsWithNoIndexOnExpireAt_whenExpiryIsUnconfigured() {
    var indexedKeys =
        mongoTemplate.getCollection(mongoTemplate.getCollectionName(NotificationAggregate.class))
            .listIndexes().into(new ArrayList<>()).stream()
            .map(index -> index.get("key", Document.class))
            .toList();

    assertThat(indexedKeys).isNotEmpty().noneMatch(key -> key.containsKey("expireAt"));
  }
}
