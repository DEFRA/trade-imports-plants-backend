package uk.gov.defra.trade.imports.plants.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mongodb.MongoCommandException;
import com.mongodb.ServerAddress;
import java.util.List;
import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.UncategorizedMongoDbException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexField;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import uk.gov.defra.trade.imports.plants.configuration.NotificationTtlConfig;

class NotificationExpiryIndexTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(TtlProperties.class, NotificationExpiryIndex.class)
          .withBean(MongoTemplate.class, () -> mock(MongoTemplate.class));

  @Configuration
  @EnableConfigurationProperties(NotificationTtlConfig.class)
  static class TtlProperties {}

  @Test
  void bean_isAbsentWhenExpiryIsNotConfigured() {
    contextRunner.run(
        context -> assertThat(context).doesNotHaveBean(NotificationExpiryIndex.class));
  }

  @Test
  void bean_isAbsentWhenExpiryIsDisabled() {
    contextRunner
        .withPropertyValues("notification.ttl.expiry.enabled=false")
        .run(context -> assertThat(context).doesNotHaveBean(NotificationExpiryIndex.class));
  }

  @Test
  void bean_existsWhenExpiryIsEnabled() {
    contextRunner
        .withPropertyValues("notification.ttl.expiry.enabled=true")
        .run(context -> assertThat(context).hasSingleBean(NotificationExpiryIndex.class));
  }

  @Test
  void replacePlainExpireAtIndexWithTtlIndex_shouldTouchNoIndex_whenExpiryIsEnabledInProd() {
    contextRunner
        .withPropertyValues(
            "notification.ttl.expiry.enabled=true", "notification.ttl.environment=prod")
        .run(context -> {
          MongoTemplate mongoTemplate = context.getBean(MongoTemplate.class);
          clearInvocations(mongoTemplate);

          context.getBean(NotificationExpiryIndex.class).replacePlainExpireAtIndexWithTtlIndex();

          verifyNoInteractions(mongoTemplate);
        });
  }

  @Test
  void replacePlainExpireAtIndexWithTtlIndex_shouldFail_whenDroppingThePlainIndexFailsForAnotherReason() {
    MongoCommandException writeConflict = new MongoCommandException(
        new BsonDocument()
            .append("ok", new BsonDouble(0.0))
            .append("code", new BsonInt32(112))
            .append("codeName", new BsonString("WriteConflict"))
            .append("errmsg", new BsonString("write conflict")),
        new ServerAddress());
    IndexOperations indexOps = mock(IndexOperations.class);
    when(indexOps.getIndexInfo()).thenReturn(List.of(new IndexInfo(
        List.of(IndexField.create("expireAt", Sort.Direction.ASC)), "expireAt", false, false, "")));
    doThrow(new UncategorizedMongoDbException("drop failed", writeConflict))
        .when(indexOps).dropIndex("expireAt");
    MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    when(mongoTemplate.indexOps(NotificationAggregate.class)).thenReturn(indexOps);
    NotificationExpiryIndex expiryIndex =
        new NotificationExpiryIndex(mongoTemplate, new NotificationTtlConfig(null, "dev"));

    assertThatThrownBy(expiryIndex::replacePlainExpireAtIndexWithTtlIndex).hasCause(writeConflict);
  }
}
