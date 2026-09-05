package uk.gov.defra.trade.imports.plants.notification;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/** Who is making the change, as supplied by the caller alongside a notification write. */
@Value
@Builder
@Jacksonized
public class ActorRequest {

    String id;
    String source;
    String userType;
    String displayName;
    String organisationId;
    String onBehalfOfOrganisationId;
}
