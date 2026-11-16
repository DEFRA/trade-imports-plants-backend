package uk.gov.defra.trade.imports.plants.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import uk.gov.defra.trade.imports.plants.notification.NotificationAggregate;

final class ExpireAtIndexes {

  private ExpireAtIndexes() {}

  static List<Document> expireAtIndexes(MongoTemplate mongoTemplate) {
    return mongoTemplate.getCollection(mongoTemplate.getCollectionName(NotificationAggregate.class))
        .listIndexes().into(new ArrayList<>()).stream()
        .filter(index -> index.get("key", Document.class).containsKey("expireAt"))
        .toList();
  }

  static void assertSingleTtlIndexNamed(MongoTemplate mongoTemplate, String indexName) {
    assertThat(expireAtIndexes(mongoTemplate)).singleElement().satisfies(index -> {
      assertThat(index.getString("name")).isEqualTo(indexName);
      assertThat(index.get("expireAfterSeconds", Number.class).longValue()).isZero();
    });
  }
}
