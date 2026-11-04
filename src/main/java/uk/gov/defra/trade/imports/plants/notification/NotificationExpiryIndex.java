package uk.gov.defra.trade.imports.plants.notification;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.stereotype.Component;
import uk.gov.defra.trade.imports.plants.configuration.NotificationTtlConfig;

/**
 * Once the application is ready, creates the Mongo TTL index that removes notifications when their
 * {@code expireAt} has passed, dropping any plain index on {@code expireAt} first. Exists only when
 * {@code notification.ttl.expiry.enabled=true}, and creates nothing in prod.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "notification.ttl.expiry", name = "enabled", havingValue = "true")
public class NotificationExpiryIndex {

  static final String INDEX_NAME = "notification_expire_at_ttl";
  private static final String EXPIRE_AT = "expireAt";

  private final MongoTemplate mongoTemplate;
  private final NotificationTtlConfig ttlConfig;

  @EventListener(ApplicationReadyEvent.class)
  public void createIndex() {
    if (ttlConfig.isProd()) {
      log.warn("Notification expiry is enabled in prod; refusing to create the TTL index");
      return;
    }
    IndexOperations indexOps = mongoTemplate.indexOps(NotificationAggregate.class);
    dropPlainExpireAtIndexes(indexOps);
    indexOps.createIndex(
        new Index().on(EXPIRE_AT, Sort.Direction.ASC).expire(Duration.ZERO).named(INDEX_NAME));
    log.info("Notification expiry TTL index {} is in place", INDEX_NAME);
  }

  private static void dropPlainExpireAtIndexes(IndexOperations indexOps) {
    indexOps.getIndexInfo().stream()
        .filter(NotificationExpiryIndex::isPlainExpireAtIndex)
        .map(IndexInfo::getName)
        .forEach(name -> {
          indexOps.dropIndex(name);
          log.info("Dropped plain index {} on expireAt", name);
        });
  }

  private static boolean isPlainExpireAtIndex(IndexInfo index) {
    List<String> keys = index.getIndexFields().stream().map(IndexField::getKey).toList();
    return index.getExpireAfter().isEmpty() && keys.equals(List.of(EXPIRE_AT));
  }
}
