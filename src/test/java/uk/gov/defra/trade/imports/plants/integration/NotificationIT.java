package uk.gov.defra.trade.imports.plants.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import uk.gov.defra.trade.imports.plants.audit.AuditRepository;
import uk.gov.defra.trade.imports.plants.audit.Result;
import uk.gov.defra.trade.imports.plants.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.plants.filter.TraderOrganisationHeaderFilter;
import uk.gov.defra.trade.imports.plants.notification.NotificationController;
import uk.gov.defra.trade.imports.plants.testsupport.TraderOrganisationTestSupport;
import uk.gov.defra.trade.imports.plants.notification.NotificationDto;
import uk.gov.defra.trade.imports.plants.notification.NotificationRepository;
import uk.gov.defra.trade.imports.plants.notification.NotificationStatus;
import uk.gov.defra.trade.imports.plants.notification.ReferenceNumberGenerator;
import uk.gov.defra.trade.imports.plants.notification.SaveNotificationDto;

class NotificationIT extends IntegrationBase {

    private static final String ORGANISATION_ID = TraderOrganisationTestSupport.ORGANISATION_ID;
    private static final String OTHER_ORGANISATION_ID = TraderOrganisationTestSupport.OTHER_ORGANISATION_ID;

    private static final String UTC_INSTANT =
        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditRepository auditRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() {
        notificationRepository.deleteAll();
        auditRepository.deleteAll();
    }

    private NotificationAggregate createDraft(List<Document> fulfilments) throws Exception {
        MvcResult result = mockMvc.perform(post("/notifications")
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SaveNotificationDto.of(
                    NotificationDto.builder().fulfilments(fulfilments).build()))))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readValue(
            result.getResponse().getContentAsString(), NotificationAggregate.class);
    }

    @Test
    void post_shouldPersistADraftWithAGeneratedPlantsReferenceNumber() throws Exception {
        NotificationAggregate created = createDraft(List.of());

        assertThat(created.getReferenceNumber()).matches(ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN);
        assertThat(created.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(created.getOrganisationId()).isEqualTo(ORGANISATION_ID);
        assertThat(notificationRepository.findByReferenceNumberAndOrganisationId(created.getReferenceNumber(), ORGANISATION_ID))
            .isPresent();
    }

    @Test
    void fulfilments_shouldRoundTripByteFaithfullyThroughMongo() throws Exception {
        List<Document> payload = List.of(
            new Document("obligationId", "consignment-details")
                .append("answers", new Document("fieldOne", "VALUE_ONE").append("quantity", 12)),
            new Document("obligationId", "transport")
                .append("answers", new Document("portOfEntry", "Dover")));

        NotificationAggregate created = createDraft(payload);

        mockMvc.perform(get("/notifications/{ref}/fulfilments", created.getReferenceNumber())
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.referenceNumber").value(created.getReferenceNumber()))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.fulfilments.length()").value(2))
            .andExpect(jsonPath("$.fulfilments[0].answers.fieldOne").value("VALUE_ONE"))
            .andExpect(jsonPath("$.fulfilments[0].answers.quantity").value(12))
            .andExpect(jsonPath("$.fulfilments[1].answers.portOfEntry").value("Dover"));
    }

    @Test
    void put_shouldReturn409_whenTheConcurrencyTokenIsStale() throws Exception {
        NotificationAggregate created = createDraft(List.of());
        mockMvc.perform(put("/notifications/{ref}", created.getReferenceNumber())
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SaveNotificationDto.of(
                    NotificationDto.builder()
                        .concurrencyToken(created.getConcurrencyToken())
                        .fulfilments(List.of(new Document("k", "first")))
                        .build()))))
            .andExpect(status().isOk());

        mockMvc.perform(put("/notifications/{ref}", created.getReferenceNumber())
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SaveNotificationDto.of(
                    NotificationDto.builder()
                        .concurrencyToken(created.getConcurrencyToken())
                        .fulfilments(List.of(new Document("k", "second")))
                        .build()))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("STALE_CONCURRENCY_TOKEN"));
    }

    @Test
    void amendThenCancel_shouldRestoreTheSubmittedFulfilments() throws Exception {
        NotificationAggregate created = createDraft(List.of(new Document("k", "submitted")));
        String reference = created.getReferenceNumber();
        mockMvc.perform(post("/notifications/{ref}/submit", reference)
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID)).andExpect(status().isOk());

        MvcResult amended = mockMvc.perform(post("/notifications/{ref}/amend", reference)
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AMEND"))
            .andReturn();
        NotificationAggregate amending = objectMapper.readValue(
            amended.getResponse().getContentAsString(), NotificationAggregate.class);

        mockMvc.perform(put("/notifications/{ref}", reference)
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SaveNotificationDto.of(
                    NotificationDto.builder()
                        .concurrencyToken(amending.getConcurrencyToken())
                        .fulfilments(List.of(new Document("k", "edited")))
                        .build()))))
            .andExpect(status().isOk());

        mockMvc.perform(post("/notifications/{ref}/cancel-amend", reference)
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID))
            .andExpect(status().isOk());

        NotificationAggregate restored =
            notificationRepository.findByReferenceNumberAndOrganisationId(reference, ORGANISATION_ID).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(restored.getFulfilments()).containsExactly(new Document("k", "submitted"));
        assertThat(restored.getSubmittedNotificationBaseline()).isNull();
        assertThat(restored.getSubmittedFulfilmentsBaseline()).isNull();
        assertThat(restored.getSubmittedAt()).isNotNull();
    }

    @Test
    void copy_shouldProduceAFreshDraftWithItsOwnReferenceNumber() throws Exception {
        NotificationAggregate created = createDraft(List.of(new Document("k", "v")));

        MvcResult result = mockMvc.perform(post("/notifications/{ref}/copy", created.getReferenceNumber())
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID)
                .param("concurrencyToken", String.valueOf(created.getConcurrencyToken())))
            .andExpect(status().isOk())
            .andReturn();
        NotificationAggregate copy = objectMapper.readValue(
            result.getResponse().getContentAsString(), NotificationAggregate.class);

        assertThat(copy.getReferenceNumber())
            .matches(ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
            .isNotEqualTo(created.getReferenceNumber());
        assertThat(copy.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(copy.getFulfilments()).containsExactly(new Document("k", "v"));
        assertThat(copy.getOrganisationId()).isEqualTo(ORGANISATION_ID);
    }

    @Test
    void list_shouldCarryTheSubmissionTimestampOnceTheNotificationIsSubmitted() throws Exception {
        NotificationAggregate created = createDraft(List.of());
        mockMvc.perform(get("/notifications")
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].status").value("DRAFT"))
            .andExpect(jsonPath("$.content[0].submittedAt").isEmpty());

        mockMvc.perform(post("/notifications/{ref}/submit", created.getReferenceNumber())
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID))
            .andExpect(status().isOk());

        mockMvc.perform(get("/notifications")
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].status").value("SUBMITTED"))
            .andExpect(jsonPath("$.content[0].submittedAt").isNotEmpty());
    }

    @Test
    void timestamps_shouldBeRfc3339InstantsWithAZSuffix_inEveryResponse() throws Exception {
        MvcResult saved = mockMvc.perform(post("/notifications")
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SaveNotificationDto.of(
                    NotificationDto.builder().fulfilments(List.of()).build()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created", matchesPattern(UTC_INSTANT)))
            .andExpect(jsonPath("$.updated", matchesPattern(UTC_INSTANT)))
            .andReturn();
        String reference = objectMapper.readValue(
            saved.getResponse().getContentAsString(), NotificationAggregate.class).getReferenceNumber();

        mockMvc.perform(post("/notifications/{ref}/submit", reference)
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created", matchesPattern(UTC_INSTANT)))
            .andExpect(jsonPath("$.updated", matchesPattern(UTC_INSTANT)))
            .andExpect(jsonPath("$.submittedAt", matchesPattern(UTC_INSTANT)));

        mockMvc.perform(get("/notifications")
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].created", matchesPattern(UTC_INSTANT)))
            .andExpect(jsonPath("$.content[0].submittedAt", matchesPattern(UTC_INSTANT)));
        mockMvc.perform(get("/notifications/{ref}/fulfilments", reference)
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.created", matchesPattern(UTC_INSTANT)))
            .andExpect(jsonPath("$.submittedAt", matchesPattern(UTC_INSTANT)));
    }

    @Test
    void post_shouldReturn400_whenTheBodyIsNotWellFormedJson() throws Exception {
        mockMvc.perform(post("/notifications")
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"notification\":"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.title").value("Malformed Request"));

        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    void softDelete_shouldRemoveTheNotificationFromTheDashboardList() throws Exception {
        NotificationAggregate created = createDraft(List.of());
        mockMvc.perform(get("/notifications")
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID))
            .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(post("/notifications/{ref}/soft-delete", created.getReferenceNumber())
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DELETED"));

        mockMvc.perform(get("/notifications")
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(notificationRepository.findByReferenceNumberAndOrganisationId(created.getReferenceNumber(), ORGANISATION_ID))
            .isPresent();
    }

    @Test
    void delete_shouldRemoveTheNotificationAndWriteASuccessAuditRecord() throws Exception {
        NotificationAggregate created = createDraft(List.of());

        mockMvc.perform(delete("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header(NotificationController.HEADER_TRACE_ID, "trace-1")
                .header(NotificationController.HEADER_USER_ID, "user-1")
                .content(objectMapper.writeValueAsString(List.of(created.getReferenceNumber()))))
            .andExpect(status().isNoContent());

        assertThat(notificationRepository.findByReferenceNumberAndOrganisationId(created.getReferenceNumber(), ORGANISATION_ID))
            .isEmpty();
        assertThat(auditRepository.findAll())
            .singleElement()
            .satisfies(audit -> {
                assertThat(audit.getResult()).isEqualTo(Result.SUCCESS);
                assertThat(audit.getUserId()).isEqualTo("user-1");
                assertThat(audit.getNotificationReferenceNumbers())
                    .containsExactly(created.getReferenceNumber());
            });
    }

    @Test
    void delete_shouldDeleteNothingAndAuditFailure_whenAReferenceIsMissing() throws Exception {
        NotificationAggregate created = createDraft(List.of());

        mockMvc.perform(delete("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header(NotificationController.HEADER_TRACE_ID, "trace-1")
                .header(NotificationController.HEADER_USER_ID, "user-1")
                .content(objectMapper.writeValueAsString(
                    List.of(created.getReferenceNumber(), "26-MSSNG1"))))
            .andExpect(status().isNotFound());

        assertThat(notificationRepository.findByReferenceNumberAndOrganisationId(created.getReferenceNumber(), ORGANISATION_ID))
            .isPresent();
        assertThat(auditRepository.findAll())
            .singleElement()
            .satisfies(audit -> assertThat(audit.getResult()).isEqualTo(Result.FAILURE));
    }

    @Test
    void referenceNumbers_shouldBeUniquelyIndexed() {
        NotificationAggregate first = notificationRepository.save(NotificationAggregate.builder()
            .referenceNumber("26-DPKEY2")
            .status(NotificationStatus.DRAFT)
            .created(Instant.now())
            .build());

        assertThat(first.getId()).isNotNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                notificationRepository.save(NotificationAggregate.builder()
                    .referenceNumber("26-DPKEY2")
                    .status(NotificationStatus.DRAFT)
                    .created(Instant.now())
                    .build()))
            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void organisationScope_list_shouldExcludeOtherOrganisationsNotifications() throws Exception {
        NotificationAggregate created = createDraft(List.of());
        mockMvc.perform(post("/notifications/{ref}/submit", created.getReferenceNumber())
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID))
            .andExpect(status().isOk());

        mockMvc.perform(get("/notifications")
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, OTHER_ORGANISATION_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void fulfilments_shouldReturn403_forAnotherOrganisation() throws Exception {
        NotificationAggregate created = createDraft(List.of());
        mockMvc.perform(post("/notifications/{ref}/submit", created.getReferenceNumber())
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID))
            .andExpect(status().isOk());

        mockMvc.perform(get("/notifications/{ref}/fulfilments", created.getReferenceNumber())
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, OTHER_ORGANISATION_ID))
            .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @MethodSource("crossOrganisationWriteRoutes")
    void writes_shouldReturn404_forAnotherOrganisation(String routeTemplate) throws Exception {
        NotificationAggregate created = createDraft(List.of());
        mockMvc.perform(post("/notifications/{ref}/submit", created.getReferenceNumber())
                .header(TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, ORGANISATION_ID))
            .andExpect(status().isOk());

        NotificationAggregate submitted =
            notificationRepository.findByReferenceNumberAndOrganisationId(
                created.getReferenceNumber(), ORGANISATION_ID).orElseThrow();
        String reference = submitted.getReferenceNumber();
        Long concurrencyToken = submitted.getConcurrencyToken();

        var request = switch (routeTemplate) {
            case "amend" -> post("/notifications/{ref}/amend", reference);
            case "put" -> put("/notifications/{ref}", reference)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SaveNotificationDto.of(
                    NotificationDto.builder().concurrencyToken(concurrencyToken).build())));
            case "submit" -> post("/notifications/{ref}/submit", reference);
            case "copy" -> post("/notifications/{ref}/copy", reference)
                .param("concurrencyToken", String.valueOf(concurrencyToken));
            case "soft-delete" -> post("/notifications/{ref}/soft-delete", reference);
            default -> throw new IllegalArgumentException(routeTemplate);
        };

        mockMvc.perform(request.header(
                TraderOrganisationHeaderFilter.ORGANISATION_ID_HEADER, OTHER_ORGANISATION_ID))
            .andExpect(status().isNotFound());
    }

    static Stream<String> crossOrganisationWriteRoutes() {
        return Stream.of("amend", "put", "submit", "copy", "soft-delete");
    }

    @ParameterizedTest
    @MethodSource("traderRoutesWithoutOrganisationHeader")
    void traderRoutes_shouldRejectRequestsWithoutOrganisationHeader(
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
        throws Exception {
        mockMvc.perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail")
                .value("Missing required header Trade-Imports-Organisation-Id"));
    }

    static Stream<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder>
        traderRoutesWithoutOrganisationHeader() {
        return Stream.of(
            get("/notifications"),
            post("/notifications"));
    }
}
