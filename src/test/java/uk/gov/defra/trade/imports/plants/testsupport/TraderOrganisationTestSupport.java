package uk.gov.defra.trade.imports.plants.testsupport;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import uk.gov.defra.trade.imports.plants.filter.TraderOrganisationHeaderFilter;

public final class TraderOrganisationTestSupport {

    public static final String ORGANISATION_ID = "5900002";
    public static final String OTHER_ORGANISATION_ID = "9999999";

    private TraderOrganisationTestSupport() {
    }

    public static MockHttpServletRequestBuilder withOrganisation(
        MockHttpServletRequestBuilder builder, String organisationId) {
        return builder.header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, organisationId);
    }
}
