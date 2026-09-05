package uk.gov.defra.trade.imports.plants.exceptions;

/**
 * Exception thrown when a requested resource is not found.
 * Will be mapped to 404 Not Found by GlobalExceptionHandler.
 */
public class NotFoundException extends TradeImportsPlantsBackendException {

    public NotFoundException(String message) {
        super(message);
    }
}
