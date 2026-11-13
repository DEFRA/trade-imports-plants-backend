package uk.gov.defra.trade.imports.plants.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.plants.integration.ExpireAtIndexes.expireAtIndexes;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
      "notification.ttl.expiry.enabled=false",
      "spring.data.mongodb.database=notification-expiry-disabled-it"
    })
class NotificationExpiryDisabledIT extends IntegrationBase {

  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void startup_shouldLeaveNotificationsWithNoIndexOnExpireAt_whenExpiryIsDisabled() {
    List<Document> indexes = expireAtIndexes(mongoTemplate);

    assertThat(indexes).isEmpty();
  }
}
