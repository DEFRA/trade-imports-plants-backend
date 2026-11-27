package uk.gov.defra.trade.imports.plants.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.testcontainers.utility.DockerImageName.parse;
import static uk.gov.defra.trade.imports.plants.testsupport.TraderOrganisationTestSupport.ORGANISATION_ID;
import static uk.gov.defra.trade.imports.plants.testsupport.TraderOrganisationTestSupport.withOrganisation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.mockserver.client.MockServerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MockServerContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;
import uk.gov.defra.trade.imports.plants.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.plants.notification.NotificationDto;
import uk.gov.defra.trade.imports.plants.notification.SaveNotificationDto;

@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
abstract class IntegrationBase {

  private static final Logger LOGGER = LoggerFactory.getLogger(IntegrationBase.class);
  static final List<String> SERVICES_TO_MOCK = List.of();

  @LocalServerPort
  int port;

  @Autowired
  protected MockMvc mockMvc;

  @Autowired
  protected MongoTemplate mongoTemplate;

  @Autowired
  private ObjectMapper objectMapper;

  @Value("${cdp.tracing.header-name}")
  private String traceIdHeader;

  private MockServerClient mockServerClient;

  static final MockServerContainer MOCK_SERVER_CONTAINER = new MockServerContainer(
      parse("mockserver/mockserver").withTag(
          "mockserver-" + MockServerClient.class.getPackage().getImplementationVersion()));

  static final DockerImageName MONGO_IMAGE = DockerImageName.parse("mongo:7.0");

  static MongoDBContainer MONGO_CONTAINER = new MongoDBContainer(MONGO_IMAGE).withExposedPorts(27017);

  static {
    Startables.deepStart(
        MONGO_CONTAINER, MOCK_SERVER_CONTAINER
    ).join();
  }

  @DynamicPropertySource
  static void setProperties(DynamicPropertyRegistry registry) {

    // Service API urls — add entries to SERVICES_TO_MOCK to register mock endpoints
    SERVICES_TO_MOCK.forEach(
        service -> registry.add("%s.url".formatted(service),
            () -> "%s/%s/".formatted(MOCK_SERVER_CONTAINER.getEndpoint(), service)));

    registry.add("spring.data.mongodb.uri", MONGO_CONTAINER::getReplicaSetUrl);
    registry.add("spring.data.mongodb.ssl.enabled", () -> "false");
  }

  /**
   * The main MockServerClient to be used for stubbing out the requests that we need to be
   * verifiable.
   *
   * @return the MockServerClient to be used for stubbing out external services.
   */
  MockServerClient usingStub() {
    if (mockServerClient == null) {
      mockServerClient = new MockServerClient(MOCK_SERVER_CONTAINER.getHost(),
          MOCK_SERVER_CONTAINER.getServerPort());
      LOGGER.info(
          "You should be able to find the dashboard here : http://{}:{}/mockserver/dashboard",
          MOCK_SERVER_CONTAINER.getHost(), MOCK_SERVER_CONTAINER.getServerPort());
    }
    return mockServerClient;
  }

  @AfterEach
  void tearDown() {
    usingStub().reset();
  }

  NotificationAggregate createNewNotification(String traceId) throws Exception {
    MvcResult result = mockMvc.perform(withOrganisation(post("/notifications"), ORGANISATION_ID)
            .header(traceIdHeader, traceId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(
                SaveNotificationDto.of(NotificationDto.builder().build()))))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(
        result.getResponse().getContentAsString(), NotificationAggregate.class);
  }

}
