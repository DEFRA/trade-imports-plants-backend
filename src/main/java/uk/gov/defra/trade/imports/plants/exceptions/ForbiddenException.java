package uk.gov.defra.trade.imports.plants.exceptions;

/** Thrown when the requested resource belongs to an organisation other than the caller's. */
public class ForbiddenException extends TradeImportsPlantsBackendException {

    public ForbiddenException(String message) {
        super(message);
    }
}
