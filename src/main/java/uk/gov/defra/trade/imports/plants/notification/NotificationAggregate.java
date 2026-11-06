package uk.gov.defra.trade.imports.plants.notification;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.Document;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;

import static java.util.Objects.requireNonNull;

@org.springframework.data.mongodb.core.mapping.Document(collection = "notification")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class NotificationAggregate {

    @Id
    private String id;

    @Indexed(unique = true, sparse = true)
    private String referenceNumber;

    @Version
    private Long concurrencyToken;

    private NotificationStatus status;

    private Instant created;

    private Instant updated;

    private Instant submittedAt;

    /**
     * When this notification becomes eligible for automatic expiry, anchored to {@code created}.
     * Set only for app-created notifications in non-prod environments (see
     * {@code NotificationService.stampExpiry}); {@code null} in prod. Mongo removes the
     * notification once this has passed.
     */
    @JsonIgnore
    private Instant expireAt;

    private Notification notification;

    private List<Document> fulfilments;

    @JsonIgnore
    private Notification submittedNotificationBaseline;

    @JsonIgnore
    private List<Document> submittedFulfilmentsBaseline;

    public Notification requireNotification() {
        return requireNonNull(notification,
            "NotificationAggregate requires a notification sub-object");
    }
}
