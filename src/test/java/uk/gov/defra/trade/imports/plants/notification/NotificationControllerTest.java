package uk.gov.defra.trade.imports.plants.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.defra.trade.imports.plants.exceptions.BadRequestException;
import uk.gov.defra.trade.imports.plants.exceptions.GlobalExceptionHandler;
import uk.gov.defra.trade.imports.plants.exceptions.NotFoundException;

@WebMvcTest(controllers = NotificationController.class)
@org.springframework.context.annotation.Import(GlobalExceptionHandler.class)
class NotificationControllerTest {

    private static final String REFERENCE = "GBN-HRP-26-ABC123";
    private static final Instant SUBMITTED_AT = Instant.parse("2026-07-14T10:30:15Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private NotificationService notificationService;

    private static NotificationAggregate aggregate(NotificationStatus status) {
        return NotificationAggregate.builder()
            .id("id-1")
            .referenceNumber(REFERENCE)
            .concurrencyToken(1L)
            .status(status)
            .created(Instant.now())
            .fulfilments(List.of(new Document("obligationId", "consignment-details")))
            .build();
    }

    private static NotificationDto sentNotification() {
        return NotificationDto.builder()
            .concurrencyToken(1L)
            .fulfilments(List.of(new Document("obligationId", "consignment-details")))
            .build();
    }

    @Test
    void post_shouldPassTheSentNotificationToTheServiceAndReturnTheAggregate() throws Exception {
        when(notificationService.saveNotification(sentNotification()))
            .thenReturn(aggregate(NotificationStatus.DRAFT));

        mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SaveNotificationDto.of(sentNotification()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.referenceNumber").value(REFERENCE))
            .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void post_shouldReturn400_whenNotificationIsMissingFromTheBody() throws Exception {
        mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());

        verify(notificationService, never()).saveNotification(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"notification\":",
        "{\"notification\":{\"created\":\"2026-07-14T10:30:15\"}}"
    })
    void post_shouldReturn400_whenTheBodyCannotBeRead(String body) throws Exception {
        mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.type")
                .value("https://api.cdp.defra.cloud/problems/malformed-request"));

        verify(notificationService, never()).saveNotification(any());
    }

    @Test
    void put_shouldPassTheReferenceAndSentNotificationToTheServiceAndReturnTheAggregate() throws Exception {
        when(notificationService.replace(REFERENCE, sentNotification()))
            .thenReturn(aggregate(NotificationStatus.DRAFT));

        mockMvc.perform(put("/notifications/{referenceNumber}", REFERENCE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SaveNotificationDto.of(sentNotification()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.referenceNumber").value(REFERENCE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"26-ABC123", "GBN-AG-26-ABC123", "GBN-HRP-26-ABC12I"})
    void put_shouldReturn400_whenReferenceNumberDoesNotMatchThePlantsPattern(String reference) throws Exception {
        mockMvc.perform(put("/notifications/{referenceNumber}", reference)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SaveNotificationDto.of(
                    NotificationDto.builder().concurrencyToken(1L).build()))))
            .andExpect(status().isBadRequest());

        verify(notificationService, never()).replace(any(), any());
    }

    @Test
    void submit_shouldReturnTheSubmittedAggregate() throws Exception {
        when(notificationService.submitNotification(REFERENCE))
            .thenReturn(aggregate(NotificationStatus.SUBMITTED));

        mockMvc.perform(post("/notifications/{referenceNumber}/submit", REFERENCE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void submit_shouldReturn400_whenTheStatusTransitionIsIllegal() throws Exception {
        when(notificationService.submitNotification(REFERENCE))
            .thenThrow(new BadRequestException("Cannot submit notification with status: DELETED"));

        mockMvc.perform(post("/notifications/{referenceNumber}/submit", REFERENCE))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Cannot submit notification with status: DELETED"));
    }

    @Test
    void amend_shouldReturnTheAmendingAggregate() throws Exception {
        when(notificationService.amendNotification(REFERENCE))
            .thenReturn(aggregate(NotificationStatus.AMEND));

        mockMvc.perform(post("/notifications/{referenceNumber}/amend", REFERENCE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AMEND"));
    }

    @Test
    void cancelAmend_shouldReturnTheRestoredAggregate() throws Exception {
        when(notificationService.cancelAmendNotification(REFERENCE))
            .thenReturn(aggregate(NotificationStatus.SUBMITTED));

        mockMvc.perform(post("/notifications/{referenceNumber}/cancel-amend", REFERENCE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void copy_shouldReturn409_whenTheSourceHasAdvanced() throws Exception {
        when(notificationService.copyNotification(REFERENCE, 1L))
            .thenThrow(new OptimisticLockingFailureException("source has advanced"));

        mockMvc.perform(post("/notifications/{referenceNumber}/copy", REFERENCE)
                .param("concurrencyToken", "1"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("STALE_CONCURRENCY_TOKEN"));
    }

    @Test
    void softDelete_shouldReturnTheDeletedAggregate() throws Exception {
        when(notificationService.softDeleteNotification(REFERENCE))
            .thenReturn(aggregate(NotificationStatus.DELETED));

        mockMvc.perform(post("/notifications/{referenceNumber}/soft-delete", REFERENCE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DELETED"));
    }

    @Test
    void findDashboardPage_shouldReturnThePaginationEnvelope() throws Exception {
        when(notificationService.findDashboardPage(1, null, null)).thenReturn(new NotificationPageResponse(
            List.of(new NotificationView.Data(
                REFERENCE, 1L, NotificationStatus.SUBMITTED, Instant.now(), SUBMITTED_AT)),
            1, 25, 1, 26L, 2));

        mockMvc.perform(get("/notifications"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].referenceNumber").value(REFERENCE))
            .andExpect(jsonPath("$.content[0].status").value("SUBMITTED"))
            .andExpect(jsonPath("$.content[0].submittedAt").value("2026-07-14T10:30:15Z"))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.size").value(25))
            .andExpect(jsonPath("$.numberOfElements").value(1))
            .andExpect(jsonPath("$.totalElements").value(26))
            .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void findDashboardPage_shouldReturn400_whenPageIsBelowOne() throws Exception {
        mockMvc.perform(get("/notifications").param("page", "0"))
            .andExpect(status().isBadRequest());

        verify(notificationService, never()).findDashboardPage(anyInt(), any(), any());
    }

    @Test
    void findFulfilments_shouldReturn404_whenTheNotificationDoesNotExist() throws Exception {
        when(notificationService.findFulfilmentsView(REFERENCE))
            .thenThrow(new NotFoundException("Cannot find notification with reference number: " + REFERENCE));

        mockMvc.perform(get("/notifications/{referenceNumber}/fulfilments", REFERENCE))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value(
                "Cannot find notification with reference number: " + REFERENCE));
    }

    @Test
    void findAllReferenceNumbers_shouldReturnThePaginationEnvelope() throws Exception {
        when(notificationService.findAllReferenceNumbers(0))
            .thenReturn(new ReferenceNumberPageResponse(List.of(REFERENCE), 0, 50, 1, 51L, 2));

        mockMvc.perform(get("/notifications/reference-numbers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0]").value(REFERENCE))
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(50))
            .andExpect(jsonPath("$.numberOfElements").value(1))
            .andExpect(jsonPath("$.totalElements").value(51))
            .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void delete_shouldReturn204_whenEveryReferenceExists() throws Exception {
        mockMvc.perform(delete("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header(NotificationController.HEADER_TRACE_ID, "trace-1")
                .header(NotificationController.HEADER_USER_ID, "user-1")
                .content(objectMapper.writeValueAsString(List.of(REFERENCE))))
            .andExpect(status().isNoContent());

        verify(notificationService).deleteByReferenceNumbers(
            List.of(REFERENCE), new AuditContext("trace-1", "user-1"));
    }

    @Test
    void delete_shouldReturn400_whenTheReferenceListIsEmpty() throws Exception {
        mockMvc.perform(delete("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header(NotificationController.HEADER_TRACE_ID, "trace-1")
                .header(NotificationController.HEADER_USER_ID, "user-1")
                .content("[]"))
            .andExpect(status().isBadRequest());

        verify(notificationService, never()).deleteByReferenceNumbers(anyList(), any());
    }

    @Test
    void delete_shouldReturn404_whenAReferenceIsMissing() throws Exception {
        doThrow(new NotFoundException("Cannot find notifications with reference numbers: " + REFERENCE))
            .when(notificationService).deleteByReferenceNumbers(anyList(), any());

        mockMvc.perform(delete("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header(NotificationController.HEADER_TRACE_ID, "trace-1")
                .header(NotificationController.HEADER_USER_ID, "user-1")
                .content(objectMapper.writeValueAsString(List.of(REFERENCE))))
            .andExpect(status().isNotFound());
    }

    @Test
    void delete_shouldReturn400_whenTheUserIdHeaderIsMissing() throws Exception {
        mockMvc.perform(delete("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header(NotificationController.HEADER_TRACE_ID, "trace-1")
                .content(objectMapper.writeValueAsString(List.of(REFERENCE))))
            .andExpect(status().isBadRequest());

        verify(notificationService, never()).deleteByReferenceNumbers(anyList(), any());
    }
}
