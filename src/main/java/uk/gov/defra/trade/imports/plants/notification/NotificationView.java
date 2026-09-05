package uk.gov.defra.trade.imports.plants.notification;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.time.LocalDateTime;

/**
 * Interface projection backing {@code GET /notifications?…} — the dashboard list row.
 *
 * <p>Carries identity and metadata only. Plants list columns are not yet agreed; when they are,
 * add {@code @Value("#{target.notification?.…}")} accessors here and matching fields on
 * {@link Data}.
 *
 * <p>{@link Data} is the concrete carrier Jackson deserializes into on the client side; Spring
 * Data returns proxy instances on the server side.
 */
@JsonDeserialize(as = NotificationView.Data.class)
public interface NotificationView {

    String getReferenceNumber();

    Long getConcurrencyToken();

    NotificationStatus getStatus();

    LocalDateTime getCreated();

    /** Jackson deserialization target — flat, matches the on-wire JSON produced by the projection. */
    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class Data implements NotificationView {
        private String referenceNumber;
        private Long concurrencyToken;
        private NotificationStatus status;
        private LocalDateTime created;
    }
}
