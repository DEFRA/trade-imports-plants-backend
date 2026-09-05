package uk.gov.defra.trade.imports.plants.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NotificationCopyMapperTest {

    private NotificationCopyMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new NotificationCopyMapper();
    }

    @Test
    void toCopyDto_shouldCarryTheFulfilmentsPayload() {
        // Given
        NotificationAggregate source = NotificationAggregate.builder()
            .fulfilments(List.of(new Document("obligationId", "consignment-details")))
            .build();

        // When
        NotificationDto copy = mapper.toCopyDto(source);

        // Then
        assertThat(copy.getFulfilments())
            .containsExactly(new Document("obligationId", "consignment-details"));
    }

    @Test
    void toCopyDto_shouldDeepCopyFulfilments_soTheCopyAndSourceCannotMutateEachOther() {
        // Given
        Document nested = new Document("answers", new Document("fieldOne", "VALUE_ONE"));
        NotificationAggregate source = NotificationAggregate.builder()
            .fulfilments(List.of(nested))
            .build();

        // When
        NotificationDto copy = mapper.toCopyDto(source);
        nested.get("answers", Document.class).put("fieldOne", "Tulipa");

        // Then
        assertThat(copy.getFulfilments().getFirst().get("answers", Document.class))
            .containsEntry("fieldOne", "VALUE_ONE");
    }

    @Test
    void toCopyDto_shouldResetIdentityAndMetadata_soTheCopyStartsAsAFreshDraft() {
        // Given a fully-populated submitted notification
        NotificationAggregate source = NotificationAggregate.builder()
            .referenceNumber("26-ABC123")
            .concurrencyToken(4L)
            .status(NotificationStatus.SUBMITTED)
            .created(LocalDateTime.now().minusDays(3))
            .updated(LocalDateTime.now())
            .build();

        // When
        NotificationDto copy = mapper.toCopyDto(source);

        // Then
        assertThat(copy.getReferenceNumber()).isNull();
        assertThat(copy.getConcurrencyToken()).isNull();
        assertThat(copy.getStatus()).isNull();
        assertThat(copy.getCreated()).isNull();
        assertThat(copy.getUpdated()).isNull();
    }

    @Test
    void toCopyDto_shouldTolerateAbsentFulfilments() {
        // Given
        NotificationAggregate source = new NotificationAggregate();

        // When
        NotificationDto copy = mapper.toCopyDto(source);

        // Then
        assertThat(copy.getFulfilments()).isNull();
    }
}
