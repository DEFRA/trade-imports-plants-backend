package uk.gov.defra.trade.imports.plants.notification;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;
import org.mapstruct.control.DeepClone;

/**
 * Deep-clones the {@link Notification} content sub-object so amend can snapshot a pre-amend
 * baseline that is independent of the live object.
 *
 * <p>{@link Notification} carries no fields yet, so today the generated clone is trivial. It is
 * MapStruct rather than a hand-written copy precisely so it stays correct — including for nested
 * objects and collections — as plants content fields are agreed and added.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR,
    mappingControl = DeepClone.class)
public interface NotificationContentMapper {

    Notification deepClone(Notification source);
}
