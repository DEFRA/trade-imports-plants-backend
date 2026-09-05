package uk.gov.defra.trade.imports.plants.notification;

import org.springframework.stereotype.Component;

/**
 * Maps a source {@link NotificationAggregate} to a {@link NotificationDto} for the copy-as-new
 * feature: the copy starts as a fresh DRAFT, so identity and metadata (reference number, status,
 * timestamps, concurrency token) are deliberately left unset and only content is carried over.
 *
 * <p>Content today is the opaque fulfilments payload alone, deep-copied so the copy and its source
 * cannot mutate each other. When plants content fields are agreed, the per-field retain/reset rules
 * belong here.
 */
@Component
public class NotificationCopyMapper {

    public NotificationDto toCopyDto(NotificationAggregate notificationAggregate) {
        return NotificationDto.builder()
            .fulfilments(Fulfilments.deepCopy(notificationAggregate.getFulfilments()))
            .build();
    }
}
