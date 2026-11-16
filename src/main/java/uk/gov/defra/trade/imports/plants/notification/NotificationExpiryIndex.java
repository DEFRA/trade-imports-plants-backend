package uk.gov.defra.trade.imports.plants.notification;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;
import uk.gov.defra.trade.imports.plants.configuration.NotificationTtlConfig;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "notification.ttl.expiry", name = "enabled", havingValue = "true")
public class NotificationExpiryIndex {

  private final MongoTemplate mongoTemplate;
  private final NotificationTtlConfig ttlConfig;

  @EventListener(ApplicationReadyEvent.class)
  public void ensureTtlIndexes() {
    if (ttlConfig.isProd()) {
      log.warn("Notification expiry is enabled in prod; refusing to create the TTL index");
      return;
    }
    String indexName = "notification_expire_at_ttl";
    mongoTemplate.indexOps(NotificationAggregate.class).createIndex(
        new Index().on("expireAt", Sort.Direction.ASC).expire(Duration.ZERO).named(indexName));
    log.info("Notification expiry TTL index {} is in place", indexName);
  }
}
