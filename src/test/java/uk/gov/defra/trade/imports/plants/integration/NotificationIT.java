package uk.gov.defra.trade.imports.plants.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import uk.gov.defra.trade.imports.plants.audit.AuditRepository;
import uk.gov.defra.trade.imports.plants.audit.Result;
import uk.gov.defra.trade.imports.plants.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.plants.notification.NotificationController;
import uk.gov.defra.trade.imports.plants.notification.NotificationDto;
import uk.gov.defra.trade.imports.plants.notification.NotificationRepository;
import uk.gov.defra.trade.imports.plants.notification.NotificationStatus;
import uk.gov.defra.trade.imports.plants.notification.ReferenceNumberGenerator;
import uk.gov.defra.trade.imports.plants.notification.SaveNotificationDto;

/**
 * Exercises the notification lifecycle against a real MongoDB, so optimistic locking, the unique
 * reference index, transactions and the opaque fulfilments round-trip are all proved against the
 * database rather than a mock.
 */
class NotificationIT extends IntegrationBase {

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
        // Given / When
        NotificationAggregate created = createDraft(List.of());

        // Then
        assertThat(created.getReferenceNumber()).matches(ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN);
        assertThat(created.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(notificationRepository.findByReferenceNumber(created.getReferenceNumber()))
            .isPresent();
    }

    @Test
    void fulfilments_shouldRoundTripByteFaithfullyThroughMongo() throws Exception {
        // Given a nested opaque payload the backend never interprets
        List<Document> payload = List.of(
            new Document("obligationId", "consignment-details")
                .append("answers", new Document("fieldOne", "VALUE_ONE").append("quantity", 12)),
            new Document("obligationId", "transport")
                .append("answers", new Document("portOfEntry", "Dover")));

        // When
        NotificationAggregate created = createDraft(payload);

        // Then — read back through the fulfilments-view projection the frontend engine uses
        mockMvc.perform(get("/notifications/{ref}/fulfilments", created.getReferenceNumber()))
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
        // Given a draft that has already been updated once
        NotificationAggregate created = createDraft(List.of());
        mockMvc.perform(put("/notifications/{ref}", created.getReferenceNumber())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SaveNotificationDto.of(
                    NotificationDto.builder()
                        .concurrencyToken(created.getConcurrencyToken())
                        .fulfilments(List.of(new Document("k", "first")))
                        .build()))))
            .andExpect(status().isOk());

        // When a second writer replays the original token
        mockMvc.perform(put("/notifications/{ref}", created.getReferenceNumber())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SaveNotificationDto.of(
                    NotificationDto.builder()
                        .concurrencyToken(created.getConcurrencyToken())
                        .fulfilments(List.of(new Document("k", "second")))
                        .build()))))
            // Then
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("STALE_CONCURRENCY_TOKEN"));
    }

    @Test
    void amendThenCancel_shouldRestoreTheSubmittedFulfilments() throws Exception {
        // Given a submitted notification
        NotificationAggregate created = createDraft(List.of(new Document("k", "submitted")));
        String reference = created.getReferenceNumber();
        mockMvc.perform(post("/notifications/{ref}/submit", reference)).andExpect(status().isOk());

        MvcResult amended = mockMvc.perform(post("/notifications/{ref}/amend", reference))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AMEND"))
            .andReturn();
        NotificationAggregate amending = objectMapper.readValue(
            amended.getResponse().getContentAsString(), NotificationAggregate.class);

        // When the amendment edits the payload and is then cancelled
        mockMvc.perform(put("/notifications/{ref}", reference)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SaveNotificationDto.of(
                    NotificationDto.builder()
                        .concurrencyToken(amending.getConcurrencyToken())
                        .fulfilments(List.of(new Document("k", "edited")))
                        .build()))))
            .andExpect(status().isOk());

        mockMvc.perform(post("/notifications/{ref}/cancel-amend", reference))
            .andExpect(status().isOk())
            .andExpect(status().isOk());

        // Then the pre-amend payload is back and the baselines are cleared
        NotificationAggregate restored =
            notificationRepository.findByReferenceNumber(reference).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(NotificationStatus.SUBMITTED);
        assertThat(restored.getFulfilments()).containsExactly(new Document("k", "submitted"));
        assertThat(restored.getSubmittedNotificationBaseline()).isNull();
        assertThat(restored.getSubmittedFulfilmentsBaseline()).isNull();
        assertThat(restored.getSubmittedAt()).isNotNull();
    }

    @Test
    void copy_shouldProduceAFreshDraftWithItsOwnReferenceNumber() throws Exception {
        // Given
        NotificationAggregate created = createDraft(List.of(new Document("k", "v")));

        // When
        MvcResult result = mockMvc.perform(post("/notifications/{ref}/copy", created.getReferenceNumber())
                .param("concurrencyToken", String.valueOf(created.getConcurrencyToken())))
            .andExpect(status().isOk())
            .andReturn();
        NotificationAggregate copy = objectMapper.readValue(
            result.getResponse().getContentAsString(), NotificationAggregate.class);

        // Then
        assertThat(copy.getReferenceNumber())
            .matches(ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)
            .isNotEqualTo(created.getReferenceNumber());
        assertThat(copy.getStatus()).isEqualTo(NotificationStatus.DRAFT);
        assertThat(copy.getFulfilments()).containsExactly(new Document("k", "v"));
    }

    @Test
    void list_shouldCarryTheSubmissionTimestampOnceTheNotificationIsSubmitted() throws Exception {
        // Given — a draft has no submission timestamp to carry
        NotificationAggregate created = createDraft(List.of());
        mockMvc.perform(get("/notifications"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].status").value("DRAFT"))
            .andExpect(jsonPath("$.content[0].submittedAt").isEmpty());

        // When
        mockMvc.perform(post("/notifications/{ref}/submit", created.getReferenceNumber()))
            .andExpect(status().isOk());

        // Then — the list projection loads submittedAt from the aggregate, not just the single read
        mockMvc.perform(get("/notifications"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].status").value("SUBMITTED"))
            .andExpect(jsonPath("$.content[0].submittedAt").isNotEmpty());
    }

    @Test
    void softDelete_shouldRemoveTheNotificationFromTheDashboardList() throws Exception {
        // Given
        NotificationAggregate created = createDraft(List.of());
        mockMvc.perform(get("/notifications"))
            .andExpect(jsonPath("$.totalElements").value(1));

        // When
        mockMvc.perform(post("/notifications/{ref}/soft-delete", created.getReferenceNumber()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DELETED"));

        // Then — the row is retained but no longer listed
        mockMvc.perform(get("/notifications"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(notificationRepository.findByReferenceNumber(created.getReferenceNumber()))
            .isPresent();
    }

    @Test
    void delete_shouldRemoveTheNotificationAndWriteASuccessAuditRecord() throws Exception {
        // Given
        NotificationAggregate created = createDraft(List.of());

        // When
        mockMvc.perform(delete("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header(NotificationController.HEADER_TRACE_ID, "trace-1")
                .header(NotificationController.HEADER_USER_ID, "user-1")
                .content(objectMapper.writeValueAsString(List.of(created.getReferenceNumber()))))
            .andExpect(status().isNoContent());

        // Then
        assertThat(notificationRepository.findByReferenceNumber(created.getReferenceNumber()))
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
        // Given
        NotificationAggregate created = createDraft(List.of());

        // When
        mockMvc.perform(delete("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header(NotificationController.HEADER_TRACE_ID, "trace-1")
                .header(NotificationController.HEADER_USER_ID, "user-1")
                .content(objectMapper.writeValueAsString(
                    List.of(created.getReferenceNumber(), "26-MSSNG1"))))
            .andExpect(status().isNotFound());

        // Then
        assertThat(notificationRepository.findByReferenceNumber(created.getReferenceNumber()))
            .isPresent();
        assertThat(auditRepository.findAll())
            .singleElement()
            .satisfies(audit -> assertThat(audit.getResult()).isEqualTo(Result.FAILURE));
    }

    @Test
    void referenceNumbers_shouldBeUniquelyIndexed() {
        // Given a persisted notification
        NotificationAggregate first = notificationRepository.save(NotificationAggregate.builder()
            .referenceNumber("26-DPKEY2")
            .status(NotificationStatus.DRAFT)
            .created(LocalDateTime.now())
            .build());

        // When / Then — a second document on the same reference is rejected by the index
        assertThat(first.getId()).isNotNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                notificationRepository.save(NotificationAggregate.builder()
                    .referenceNumber("26-DPKEY2")
                    .status(NotificationStatus.DRAFT)
                    .created(LocalDateTime.now())
                    .build()))
            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
}
