package uk.gov.defra.trade.imports.plants.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static uk.gov.defra.trade.imports.plants.testsupport.TraderOrganisationTestSupport.ORGANISATION_ID;
import static uk.gov.defra.trade.imports.plants.testsupport.TraderOrganisationTestSupport.withOrganisation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TimingIT extends IntegrationBase {

  @Autowired private MeterRegistry meterRegistry;

  @Test
  void postNotification_shouldAddOneCallToItsControllerAndServiceTimers() throws Exception {
    String controllerTimer = "controller.postNotification.time";
    String serviceTimer = "service.saveNotification.time";
    long controllerBefore = timerCount(controllerTimer);
    long serviceBefore = timerCount(serviceTimer);

    createNewNotification("timing-it");

    assertThat(timerCount(controllerTimer)).isEqualTo(controllerBefore + 1);
    assertThat(timerCount(serviceTimer)).isEqualTo(serviceBefore + 1);
  }

  @Test
  void getFulfilments_shouldAddOneCallToItsControllerAndServiceTimers() throws Exception {
    String controllerTimer = "controller.getFulfilments.time";
    String serviceTimer = "service.findFulfilmentsView.time";
    String referenceNumber = createNewNotification("timing-it").getReferenceNumber();
    long controllerBefore = timerCount(controllerTimer);
    long serviceBefore = timerCount(serviceTimer);

    mockMvc
        .perform(
            withOrganisation(
                get("/notifications/{referenceNumber}/fulfilments", referenceNumber),
                ORGANISATION_ID))
        .andExpect(status().isOk());

    assertThat(timerCount(controllerTimer)).isEqualTo(controllerBefore + 1);
    assertThat(timerCount(serviceTimer)).isEqualTo(serviceBefore + 1);
  }

  private long timerCount(String name) {
    return meterRegistry.find(name).timers().stream().mapToLong(Timer::count).sum();
  }
}
