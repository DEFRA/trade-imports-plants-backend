package uk.gov.defra.trade.imports.plants.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

class NotificationAggregateTest {

    @Test
    void requireNotification_shouldReturnTheContentSubObject_whenPresent() {
        // Given
        Notification content = new Notification();
        NotificationAggregate aggregate = NotificationAggregate.builder()
            .notification(content)
            .build();

        // When
        Notification result = aggregate.requireNotification();

        // Then
        assertThat(result).isSameAs(content);
    }

    @Test
    void requireNotification_shouldThrow_whenContentAbsent() {
        // Given
        NotificationAggregate aggregate = new NotificationAggregate();

        // When / Then
        assertThatThrownBy(aggregate::requireNotification)
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("requires a notification sub-object");
    }

    @Test
    void fulfilments_shouldRoundTripAnArbitraryOpaquePayload() {
        // Given — the backend never interprets this shape
        List<Document> payload = List.of(
            new Document("obligationId", "consignment-details")
                .append("answers", new Document("fieldOne", "VALUE_ONE")),
            new Document("obligationId", "transport").append("answers", new Document()));

        // When
        NotificationAggregate aggregate = NotificationAggregate.builder()
            .fulfilments(payload)
            .build();

        // Then
        assertThat(aggregate.getFulfilments()).isEqualTo(payload);
    }
}
