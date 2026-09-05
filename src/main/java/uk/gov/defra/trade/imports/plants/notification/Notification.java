package uk.gov.defra.trade.imports.plants.notification;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * The typed content sub-object of {@link NotificationAggregate}, sitting symmetric to the opaque
 * {@code fulfilments} payload.
 *
 * <p>Deliberately empty: the plants journey has no agreed data model yet, and the frontend engine's
 * whole state round-trips through {@code fulfilments}, so nothing needs a typed home here until a
 * plants field is actually settled. Add fields as they are agreed — every seam that snapshots,
 * clones or persists this type already works generically.
 */
@Data
@SuperBuilder(toBuilder = true)
@NoArgsConstructor
public class Notification {
}
