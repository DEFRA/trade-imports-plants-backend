package uk.gov.defra.trade.imports.plants.exceptions;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns application exceptions into RFC 7807 {@link ProblemDetail} responses that carry the
 * request's trace id, and logs each one with that trace id.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  private static final String MDC_TRACE_ID = "trace.id";

  /**
   * Handle validation errors (400 Bad Request).
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidationException(MethodArgumentNotValidException ex) {
    String traceId = MDC.get(MDC_TRACE_ID);
    log.warn("Validation error (trace: {})", traceId, ex);

    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        "Validation failed for one or more fields"
    );

    problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/validation-error"));
    problemDetail.setTitle("Validation Error");

    if (traceId != null) {
      problemDetail.setProperty("traceId", traceId);
    }

    Map<String, String> errors = new HashMap<>();
    for (FieldError error : ex.getBindingResult().getFieldErrors()) {
      errors.put(error.getField(), error.getDefaultMessage());
    }
    problemDetail.setProperty("errors", errors);

    return problemDetail;
  }

  /**
   * Handle a request body the parser could not read (400 Bad Request): malformed JSON, or a value
   * that does not fit its field, such as a date-only field sent with a time. The response has no
   * {@code errors} map and does not echo the parser message.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadableRequestBody(HttpMessageNotReadableException ex) {
    String traceId = MDC.get(MDC_TRACE_ID);
    log.warn("Unreadable request body (trace: {})", traceId, ex);

    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        "Request body could not be read. Check the JSON is well-formed, that each date-only "
            + "field is a date, for example 2026-12-12, and that each timestamp is an "
            + "RFC 3339 instant, for example 2026-12-12T00:00:00Z"
    );

    problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/malformed-request"));
    problemDetail.setTitle("Malformed Request");

    if (traceId != null) {
      problemDetail.setProperty("traceId", traceId);
    }

    return problemDetail;
  }

  /**
   * Handle method-parameter validation errors from {@code @Validated} controllers (400 Bad
   * Request) — for example a path variable that fails its {@code @Pattern}.
   */
  @ExceptionHandler(ConstraintViolationException.class)
  public ProblemDetail handleConstraintViolationException(ConstraintViolationException ex) {
    String traceId = MDC.get(MDC_TRACE_ID);
    log.warn("Constraint violation (trace: {})", traceId, ex);

    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        "Validation failed for one or more fields"
    );

    problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/validation-error"));
    problemDetail.setTitle("Validation Error");

    if (traceId != null) {
      problemDetail.setProperty("traceId", traceId);
    }

    Map<String, String> errors = new HashMap<>();
    for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
      errors.put(lastPathSegment(violation), violation.getMessage());
    }
    problemDetail.setProperty("errors", errors);

    return problemDetail;
  }

  private static String lastPathSegment(ConstraintViolation<?> violation) {
    String propertyPath = violation.getPropertyPath().toString();
    return propertyPath.contains(".")
        ? propertyPath.substring(propertyPath.lastIndexOf('.') + 1)
        : propertyPath;
  }

  /**
   * Handle application-level bad-request errors (400 Bad Request).
   */
  @ExceptionHandler(BadRequestException.class)
  public ProblemDetail handleBadRequestException(BadRequestException ex) {
    String traceId = MDC.get(MDC_TRACE_ID);
    log.warn("Bad request (trace: {})", traceId, ex);

    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        ex.getMessage()
    );

    problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/bad-request"));
    problemDetail.setTitle("Bad Request");

    if (traceId != null) {
      problemDetail.setProperty("traceId", traceId);
    }

    return problemDetail;
  }

  /**
   * Handle a write against a stale concurrency token (409 Conflict, code
   * {@code STALE_CONCURRENCY_TOKEN}).
   */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ProblemDetail handleOptimisticLockingFailure(OptimisticLockingFailureException ex) {
    String traceId = MDC.get(MDC_TRACE_ID);
    log.warn("Stale-concurrencyToken conflict (trace: {})", traceId, ex);

    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT,
        "The record was modified by another request; refresh and try again."
    );

    problemDetail.setType(
        URI.create("https://api.cdp.defra.cloud/problems/stale-concurrency-token"));
    problemDetail.setTitle("Stale Concurrency Token");
    problemDetail.setProperty("code", "STALE_CONCURRENCY_TOKEN");

    if (traceId != null) {
      problemDetail.setProperty("traceId", traceId);
    }

    return problemDetail;
  }

  /**
   * Handle not found errors (404 Not Found).
   */
  @ExceptionHandler(NotFoundException.class)
  public ProblemDetail handleNotFoundException(NotFoundException ex) {
    String traceId = MDC.get(MDC_TRACE_ID);
    log.warn("Resource not found (trace: {})", traceId, ex);

    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.NOT_FOUND,
        ex.getMessage()
    );

    problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/not-found"));
    problemDetail.setTitle("Resource Not Found");

    if (traceId != null) {
      problemDetail.setProperty("traceId", traceId);
    }

    return problemDetail;
  }

  /**
   * Handle conflict errors (409 Conflict).
   */
  @ExceptionHandler(ConflictException.class)
  public ProblemDetail handleConflictException(ConflictException ex) {
    String traceId = MDC.get(MDC_TRACE_ID);
    log.warn("Resource conflict (trace: {})", traceId, ex);

    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.CONFLICT,
        ex.getMessage()
    );

    problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/conflict"));
    problemDetail.setTitle("Resource Conflict");

    if (traceId != null) {
      problemDetail.setProperty("traceId", traceId);
    }

    return problemDetail;
  }

  /**
   * Handle unexpected runtime errors (500 Internal Server Error).
   */
  @ExceptionHandler({
      RuntimeException.class,
      IllegalStateException.class,
      IllegalArgumentException.class
  })
  public ProblemDetail handleException(Exception ex) {
    String traceId = MDC.get(MDC_TRACE_ID);
    log.error("Unexpected error (trace: {})", traceId, ex);

    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "An unexpected error occurred. Please try again later."
    );

    problemDetail.setType(URI.create("https://api.cdp.defra.cloud/problems/internal-error"));
    problemDetail.setTitle("Internal Server Error");

    if (traceId != null) {
      problemDetail.setProperty("traceId", traceId);
    }

    return problemDetail;
  }
}
