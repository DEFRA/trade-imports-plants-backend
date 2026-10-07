package uk.gov.defra.trade.imports.plants.notification;

import java.time.Instant;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.bson.Document;

/**
 * Wire type for reads and writes of a notification: the metadata half of
 * {@link NotificationAggregate} plus the opaque fulfilments payload. Carries no typed content
 * fields yet — see {@link Notification}.
 */
@Data
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class NotificationDto {

    private String referenceNumber;

    private NotificationStatus status;

    private Instant created;

    private Instant updated;

    private Long concurrencyToken;

    /** Opaque obligation-fulfilment payload — persisted byte-faithfully. */
    private List<Document> fulfilments;
}
