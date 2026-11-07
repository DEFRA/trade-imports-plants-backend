package uk.gov.defra.trade.imports.plants.exceptions;

/**
 * Caller is authenticated and scoped, but not permitted to access the resource (403).
 */
public class ForbiddenException extends TradeImportsPlantsBackendException {

    public ForbiddenException(String message) {
        super(message);
    }
}
