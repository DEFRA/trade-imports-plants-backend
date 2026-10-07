package uk.gov.defra.trade.imports.plants.configuration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.defra.trade.imports.plants.exceptions.GlobalExceptionHandler;

/**
 * A date-only field accepts {@code 2026-07-21} and nothing else. Jackson's
 * {@code LocalDateDeserializer} is lenient by default and would keep the date part of
 * {@code 2026-07-21T00:00:00Z}; {@link StrictLocalDateModule} turns that off, and the caller gets
 * 400 rather than a silently trimmed value.
 *
 * <p>Plants has no typed date-only field yet, so the module is proved against
 * {@link ProbeController}. Nothing here imports the module: the slice picks it up as a Jackson
 * {@code Module} bean, the same way the real controllers will.
 */
@WebMvcTest(controllers = StrictLocalDateModuleTest.ProbeController.class)
@Import({StrictLocalDateModuleTest.ProbeController.class, GlobalExceptionHandler.class})
class StrictLocalDateModuleTest {

    private static final String PROBE_PATH = "/test/date-probe";

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {
        "2026-07-21T00:00:00Z",
        "2026-07-21T00:00:00",
        "2026-07-21T00:00:00+01:00"
    })
    void post_shouldReturn400_whenADateOnlyFieldCarriesATimeOrAnOffset(String value)
        throws Exception {
        // When / Then — the date-only field is sent with a time or an offset
        mockMvc.perform(post(PROBE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"arrivalDate\":\"%s\"}".formatted(value)))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.type")
                .value("https://api.cdp.defra.cloud/problems/malformed-request"));
    }

    @Test
    void post_shouldBindTheCalendarDate_whenADateOnlyFieldIsADate() throws Exception {
        // When / Then — the date-only field is sent as a plain calendar date
        mockMvc.perform(post(PROBE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"arrivalDate\":\"2026-07-21\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.arrivalDate").value("2026-07-21"));
    }

    /** Stand-in for the first request body that will carry a typed date-only field. */
    record DateProbe(LocalDate arrivalDate) {
    }

    @RestController
    static class ProbeController {

        @PostMapping(PROBE_PATH)
        DateProbe echo(@RequestBody DateProbe probe) {
            return probe;
        }
    }
}
