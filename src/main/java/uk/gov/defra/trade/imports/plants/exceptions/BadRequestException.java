package uk.gov.defra.trade.imports.plants.exceptions;

/**
 * Exception thrown when application-level validation rejects a request — for example an illegal
 * notification status transition. Mapped to 400 Bad Request by GlobalExceptionHandler.
 */
public class BadRequestException extends TradeImportsPlantsBackendException {

    public BadRequestException(String message) {
        super(message);
    }

    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
