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
import java.time.LocalDateTime;
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
    private static final LocalDateTime SUBMITTED_AT = LocalDateTime.of(2026, 7, 14, 10, 30, 15);

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
            .created(LocalDateTime.now())
            .fulfilments(List.of(new Document("obligationId", "consignment-details")))
            .build();
    }

    @Test
    void post_shouldCreateNotificationAndReturnTheAggregate() throws Exception {
        // Given
        when(notificationService.saveNotification(any())).thenReturn(aggregate(NotificationStatus.DRAFT));

        // When / Then
        mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    SaveNotificationDto.of(NotificationDto.builder().build()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.referenceNumber").value(REFERENCE))
            .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void post_shouldReturn400_whenNotificationIsMissingFromTheBody() throws Exception {
        // When / Then
        mockMvc.perform(post("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());

        verify(notificationService, never()).saveNotification(any());
    }

    @Test
    void put_shouldReplaceNotificationContent() throws Exception {
        // Given
        when(notificationService.replace(any(), any())).thenReturn(aggregate(NotificationStatus.DRAFT));

        // When / Then
        mockMvc.perform(put("/notifications/{ref}", REFERENCE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SaveNotificationDto.of(
                    NotificationDto.builder().concurrencyToken(1L).build()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.referenceNumber").value(REFERENCE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"26-ABC123", "GBN-AG-26-ABC123", "GBN-HRP-26-ABC12I"})
    void put_shouldReturn400_whenReferenceNumberDoesNotMatchThePlantsPattern(String reference) throws Exception {
        // When — an unprefixed, other-journey or malformed reference is supplied
        mockMvc.perform(put("/notifications/{ref}", reference)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(SaveNotificationDto.of(
                    NotificationDto.builder().concurrencyToken(1L).build()))))
            .andExpect(status().isBadRequest());

        // Then
        verify(notificationService, never()).replace(any(), any());
    }

    @Test
    void submit_shouldReturnTheSubmittedAggregate() throws Exception {
        // Given
        when(notificationService.submitNotification(REFERENCE))
            .thenReturn(aggregate(NotificationStatus.SUBMITTED));

        // When / Then
        mockMvc.perform(post("/notifications/{ref}/submit", REFERENCE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void submit_shouldReturn400_whenTheStatusTransitionIsIllegal() throws Exception {
        // Given
        when(notificationService.submitNotification(REFERENCE))
            .thenThrow(new BadRequestException("Cannot submit notification with status: DELETED"));

        // When / Then
        mockMvc.perform(post("/notifications/{ref}/submit", REFERENCE))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Cannot submit notification with status: DELETED"));
    }

    @Test
    void amend_shouldReturnTheAmendingAggregate() throws Exception {
        // Given
        when(notificationService.amendNotification(REFERENCE))
            .thenReturn(aggregate(NotificationStatus.AMEND));

        // When / Then
        mockMvc.perform(post("/notifications/{ref}/amend", REFERENCE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AMEND"));
    }

    @Test
    void cancelAmend_shouldReturnTheRestoredAggregate() throws Exception {
        // Given
        when(notificationService.cancelAmendNotification(REFERENCE))
            .thenReturn(aggregate(NotificationStatus.SUBMITTED));

        // When / Then
        mockMvc.perform(post("/notifications/{ref}/cancel-amend", REFERENCE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void copy_shouldReturn409_whenTheSourceHasAdvanced() throws Exception {
        // Given
        when(notificationService.copyNotification(REFERENCE, 1L))
            .thenThrow(new OptimisticLockingFailureException("source has advanced"));

        // When / Then
        mockMvc.perform(post("/notifications/{ref}/copy", REFERENCE)
                .param("concurrencyToken", "1"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("STALE_CONCURRENCY_TOKEN"));
    }

    @Test
    void softDelete_shouldReturnTheDeletedAggregate() throws Exception {
        // Given
        when(notificationService.softDeleteNotification(REFERENCE))
            .thenReturn(aggregate(NotificationStatus.DELETED));

        // When / Then
        mockMvc.perform(post("/notifications/{ref}/soft-delete", REFERENCE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DELETED"));
    }

    @Test
    void findAll_shouldReturnThePaginationEnvelope() throws Exception {
        // Given
        when(notificationService.findAll(1, null, null)).thenReturn(new NotificationPageResponse(
            List.of(new NotificationView.Data(
                REFERENCE, 1L, NotificationStatus.SUBMITTED, LocalDateTime.now(), SUBMITTED_AT)),
            1, 25, 1, 1L, 1));

        // When / Then
        mockMvc.perform(get("/notifications"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].referenceNumber").value(REFERENCE))
            .andExpect(jsonPath("$.content[0].status").value("SUBMITTED"))
            .andExpect(jsonPath("$.content[0].submittedAt").value(SUBMITTED_AT.toString()))
            .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void findAll_shouldReturn400_whenPageIsBelowOne() throws Exception {
        // When / Then
        mockMvc.perform(get("/notifications").param("page", "0"))
            .andExpect(status().isBadRequest());

        verify(notificationService, never()).findAll(anyInt(), any(), any());
    }

    @Test
    void findFulfilments_shouldReturn404_whenTheNotificationDoesNotExist() throws Exception {
        // Given
        when(notificationService.findFulfilmentsView(REFERENCE))
            .thenThrow(new NotFoundException("Cannot find notification with reference number: " + REFERENCE));

        // When / Then
        mockMvc.perform(get("/notifications/{ref}/fulfilments", REFERENCE))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value(
                "Cannot find notification with reference number: " + REFERENCE));
    }

    @Test
    void findAllReferenceNumbers_shouldReturnThePaginationEnvelope() throws Exception {
        // Given
        when(notificationService.findAllReferenceNumbers(0))
            .thenReturn(new ReferenceNumberPageResponse(List.of(REFERENCE), 0, 50, 1, 1L, 1));

        // When / Then
        mockMvc.perform(get("/notifications/reference-numbers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0]").value(REFERENCE));
    }

    @Test
    void delete_shouldReturn204_whenEveryReferenceExists() throws Exception {
        // When / Then
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
        // When / Then
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
        // Given
        doThrow(new NotFoundException("Cannot find notifications with reference numbers: " + REFERENCE))
            .when(notificationService).deleteByReferenceNumbers(anyList(), any());

        // When / Then
        mockMvc.perform(delete("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header(NotificationController.HEADER_TRACE_ID, "trace-1")
                .header(NotificationController.HEADER_USER_ID, "user-1")
                .content(objectMapper.writeValueAsString(List.of(REFERENCE))))
            .andExpect(status().isNotFound());
    }

    @Test
    void delete_shouldReturn400_whenTheUserIdHeaderIsMissing() throws Exception {
        // When / Then
        mockMvc.perform(delete("/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header(NotificationController.HEADER_TRACE_ID, "trace-1")
                .content(objectMapper.writeValueAsString(List.of(REFERENCE))))
            .andExpect(status().isBadRequest());

        verify(notificationService, never()).deleteByReferenceNumbers(anyList(), any());
    }
}
