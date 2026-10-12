package uk.gov.defra.trade.imports.plants.exceptions;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void handleValidationException_shouldReturnBadRequestWithFieldErrors() {
        // Given
        String traceId = "test-trace-123";
        MDC.put("trace.id", traceId);

        MethodArgumentNotValidException exception = createValidationException(
            new FieldError("notification", "origin", "must not be null"),
            new FieldError("notification", "commodity", "must not be blank")
        );

        // When
        ProblemDetail problemDetail = exceptionHandler.handleValidationException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Validation Error");
        assertThat(problemDetail.getDetail()).isEqualTo("Validation failed for one or more fields");
        assertThat(problemDetail.getType()).isEqualTo(URI.create("https://api.cdp.defra.cloud/problems/validation-error"));
        assertThat(problemDetail.getProperties()).containsKey("traceId");
        assertThat(problemDetail.getProperties().get("traceId")).isEqualTo(traceId);

        @SuppressWarnings("unchecked")
        Map<String, String> errors = (Map<String, String>) problemDetail.getProperties().get("errors");
        assertThat(errors).hasSize(2);
        assertThat(errors.get("origin")).isEqualTo("must not be null");
        assertThat(errors.get("commodity")).isEqualTo("must not be blank");
    }

    @Test
    void handleValidationException_shouldHandleNullTraceId() {
        // Given - no trace ID in MDC
        MethodArgumentNotValidException exception = createValidationException(
            new FieldError("notification", "origin", "must not be null")
        );

        // When
        ProblemDetail problemDetail = exceptionHandler.handleValidationException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        // When traceId is null, the property is never set, so properties may be null or not contain traceId
        Map<String, Object> properties = problemDetail.getProperties();
        if (properties != null) {
            assertThat(properties).doesNotContainKey("traceId");
        }
    }

    @Test
    void handleUnreadableRequestBody_shouldReturnBadRequestWithoutEchoingTheParserMessage() {
        // Given
        String traceId = "test-trace-321";
        MDC.put("trace.id", traceId);
        HttpMessageNotReadableException exception = unreadableRequestBodyException();

        // When
        ProblemDetail problemDetail = exceptionHandler.handleUnreadableRequestBody(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Malformed Request");
        assertThat(problemDetail.getType())
            .isEqualTo(URI.create("https://api.cdp.defra.cloud/problems/malformed-request"));
        assertThat(problemDetail.getProperties()).containsEntry("traceId", traceId);
        assertThat(problemDetail.getDetail()).isEqualTo(
            "Request body could not be read. Check the JSON is well-formed, that each date-only "
                + "field is a date, for example 2026-12-12, and that each timestamp is "
                + "an RFC 3339 instant, for example 2026-12-12T00:00:00Z");

        // An unreadable body is its own problem type, not field validation: nothing bound, so
        // there is no errors map - which is what keeps one type URI to one response shape.
        assertThat(problemDetail.getProperties()).doesNotContainKey("errors");

        String[] parserMessageMarkers = {
            "JSON parse error",
            "java.time.LocalDate",
            "DateTimeParseException",
            "1999-07-04T00:00:00Z",
            "NotificationRequest",
            "reference chain"
        };

        // Pin the fixture: the parser message really does carry every marker, so the non-leak
        // assertion below cannot pass just because the marker was never there to leak.
        assertThat(exception.getMessage()).contains(parserMessageMarkers);

        // The parser message quotes the submitted value and names internal types - none of it
        // may reach the caller.
        assertThat(problemDetail.getDetail()).doesNotContain(parserMessageMarkers);
    }

    @Test
    void handleUnreadableRequestBody_shouldHandleNullTraceId() {
        // Given - no trace ID in MDC
        HttpMessageNotReadableException exception = unreadableRequestBodyException();

        // When
        ProblemDetail problemDetail = exceptionHandler.handleUnreadableRequestBody(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        // When traceId is null, the property is never set, so properties may be null or not contain traceId
        Map<String, Object> properties = problemDetail.getProperties();
        if (properties != null) {
            assertThat(properties).doesNotContainKey("traceId");
        }
    }

    /**
     * An instant where a date-only {@code LocalDate} is required, carrying the kind of Jackson
     * message the handler logs but must not echo back to the caller.
     */
    private static HttpMessageNotReadableException unreadableRequestBodyException() {
        String parserMessage = "JSON parse error: Cannot deserialize value of type "
            + "`java.time.LocalDate` from String \"1999-07-04T00:00:00Z\": Failed to deserialize "
            + "java.time.LocalDate: (java.time.format.DateTimeParseException) Text "
            + "'1999-07-04T00:00:00Z' could not be parsed, unparsed text found at index 10 "
            + "(through reference chain: "
            + "uk.gov.defra.trade.imports.plants.notification.NotificationRequest[\"arrivalDate\"])";
        return new HttpMessageNotReadableException(
            parserMessage,
            new MockHttpInputMessage(
                "{\"arrivalDate\":\"1999-07-04T00:00:00Z\"}".getBytes(UTF_8)));
    }

    @Test
    void handleNotFoundException_shouldReturnNotFound() {
        // Given
        String traceId = "test-trace-456";
        MDC.put("trace.id", traceId);
        NotFoundException exception = new NotFoundException("Notification with id 12345 not found");

        // When
        ProblemDetail problemDetail = exceptionHandler.handleNotFoundException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Resource Not Found");
        assertThat(problemDetail.getDetail()).isEqualTo("Notification with id 12345 not found");
        assertThat(problemDetail.getType()).isEqualTo(URI.create("https://api.cdp.defra.cloud/problems/not-found"));
        assertThat(problemDetail.getProperties()).containsKey("traceId");
        assertThat(problemDetail.getProperties().get("traceId")).isEqualTo(traceId);
    }

    @Test
    void handleNotFoundException_shouldHandleNullTraceId() {
        // Given - no trace ID in MDC
        NotFoundException exception = new NotFoundException("Resource not found");

        // When
        ProblemDetail problemDetail = exceptionHandler.handleNotFoundException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        // When traceId is null, the property is never set, so properties may be null or not contain traceId
        Map<String, Object> properties = problemDetail.getProperties();
        if (properties != null) {
            assertThat(properties).doesNotContainKey("traceId");
        }
    }

    @Test
    void handleConflictException_shouldReturnConflict() {
        // Given
        String traceId = "test-trace-789";
        MDC.put("trace.id", traceId);
        ConflictException exception = new ConflictException("Notification with reference DRAFT.IMP.2026.001 already exists");

        // When
        ProblemDetail problemDetail = exceptionHandler.handleConflictException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Resource Conflict");
        assertThat(problemDetail.getDetail()).isEqualTo("Notification with reference DRAFT.IMP.2026.001 already exists");
        assertThat(problemDetail.getType()).isEqualTo(URI.create("https://api.cdp.defra.cloud/problems/conflict"));
        assertThat(problemDetail.getProperties()).containsKey("traceId");
        assertThat(problemDetail.getProperties().get("traceId")).isEqualTo(traceId);
    }

    @Test
    void handleConflictException_shouldHandleNullTraceId() {
        // Given - no trace ID in MDC
        ConflictException exception = new ConflictException("Resource conflict");

        // When
        ProblemDetail problemDetail = exceptionHandler.handleConflictException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        // When traceId is null, the property is never set, so properties may be null or not contain traceId
        Map<String, Object> properties = problemDetail.getProperties();
        if (properties != null) {
            assertThat(properties).doesNotContainKey("traceId");
        }
    }

    @Test
    void handleException_shouldReturnInternalServerError_forRuntimeException() {
        // Given
        String traceId = "test-trace-999";
        MDC.put("trace.id", traceId);
        RuntimeException exception = new RuntimeException("Unexpected database error");

        // When
        ProblemDetail problemDetail = exceptionHandler.handleException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(problemDetail.getTitle()).isEqualTo("Internal Server Error");
        assertThat(problemDetail.getDetail()).isEqualTo("An unexpected error occurred. Please try again later.");
        assertThat(problemDetail.getType()).isEqualTo(URI.create("https://api.cdp.defra.cloud/problems/internal-error"));
        assertThat(problemDetail.getProperties()).containsKey("traceId");
        assertThat(problemDetail.getProperties().get("traceId")).isEqualTo(traceId);
    }

    @Test
    void handleException_shouldReturnInternalServerError_forIllegalStateException() {
        // Given
        IllegalStateException exception = new IllegalStateException("Invalid state");

        // When
        ProblemDetail problemDetail = exceptionHandler.handleException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    @Test
    void handleException_shouldReturnInternalServerError_forIllegalArgumentException() {
        // Given
        IllegalArgumentException exception = new IllegalArgumentException("Invalid argument");

        // When
        ProblemDetail problemDetail = exceptionHandler.handleException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
    }

    @Test
    void handleException_shouldHandleNullTraceId() {
        // Given - no trace ID in MDC
        RuntimeException exception = new RuntimeException("Error");

        // When
        ProblemDetail problemDetail = exceptionHandler.handleException(exception);

        // Then
        assertThat(problemDetail).isNotNull();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        // When traceId is null, the property is never set, so properties may be null or not contain traceId
        Map<String, Object> properties = problemDetail.getProperties();
        if (properties != null) {
            assertThat(properties).doesNotContainKey("traceId");
        }
    }

    private MethodArgumentNotValidException createValidationException(FieldError... fieldErrors) {
        try {
            // Create a real MethodParameter with an actual method to avoid NullPointerException
            Method testMethod = this.getClass().getDeclaredMethod("setUp");
            MethodParameter methodParameter = new MethodParameter(testMethod, -1);

            BindingResult bindingResult = mock(BindingResult.class);
            when(bindingResult.getFieldErrors()).thenReturn(List.of(fieldErrors));

            return new MethodArgumentNotValidException(methodParameter, bindingResult);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Failed to create test MethodParameter", e);
        }
    }
}
