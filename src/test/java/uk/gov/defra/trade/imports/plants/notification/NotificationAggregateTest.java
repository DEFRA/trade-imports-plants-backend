package uk.gov.defra.trade.imports.plants.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class NotificationAggregateTest {

    @Test
    void fulfilments_shouldRoundTripAnArbitraryOpaquePayload() {
        List<Document> payload = List.of(
            new Document("obligationId", "consignment-details")
                .append("answers", new Document("fieldOne", "VALUE_ONE")),
            new Document("obligationId", "transport").append("answers", new Document()));

        NotificationAggregate aggregate = NotificationAggregate.builder()
            .fulfilments(payload)
            .build();

        assertThat(aggregate.getFulfilments()).isEqualTo(payload);
    }
}
