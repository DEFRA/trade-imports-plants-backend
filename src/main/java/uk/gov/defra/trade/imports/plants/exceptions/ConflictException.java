package uk.gov.defra.trade.imports.plants.exceptions;

/**
 * Exception thrown when a resource conflict occurs (e.g., duplicate key).
 * Will be mapped to 409 Conflict by GlobalExceptionHandler.
 */
public class ConflictException extends TradeImportsPlantsBackendException {

    public ConflictException(String message) {
        super(message);
    }
}
