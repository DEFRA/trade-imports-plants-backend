package uk.gov.defra.trade.imports.plants.integration;

import static uk.gov.defra.trade.imports.plants.integration.ExpireAtIndexes.assertSingleTtlIndexNamed;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
    properties = {
        "notification.ttl.expiry.enabled=true",
        "spring.data.mongodb.database=notification-expiry-startup-it"
    })
class NotificationExpiryStartupIT extends IntegrationBase {

  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void startup_shouldCreateTheNamedTtlIndexWithZeroExpireAfterSeconds_whenExpiryIsEnabled() {
    assertSingleTtlIndexNamed(mongoTemplate, "notification_expire_at_ttl");
  }
}
