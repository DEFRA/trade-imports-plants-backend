package uk.gov.defra.trade.imports.plants.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;
import uk.gov.defra.trade.imports.plants.notification.NotificationAggregate;

@TestPropertySource(
    properties = {
        "notification.ttl.expiry.enabled=true",
        "spring.data.mongodb.database=notification-expiry-startup-it"
    })
class NotificationExpiryStartupIT extends IntegrationBase {

  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void startup_shouldCreateTheNamedTtlIndexWithZeroExpireAfterSeconds_whenExpiryIsEnabled() {
    var expireAtIndexes =
        mongoTemplate.getCollection(mongoTemplate.getCollectionName(NotificationAggregate.class))
            .listIndexes().into(new ArrayList<>()).stream()
            .filter(index -> index.get("key", Document.class).containsKey("expireAt"))
            .toList();

    assertThat(expireAtIndexes).singleElement().satisfies(index -> {
      assertThat(index.getString("name")).isEqualTo("notification_expire_at_ttl");
      assertThat(index.get("expireAfterSeconds", Number.class).longValue()).isZero();
    });
  }
}
