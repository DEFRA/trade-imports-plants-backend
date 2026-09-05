package uk.gov.defra.trade.imports.plants.exceptions;

/** Base type for application-level failures raised by this service. */
public abstract class TradeImportsPlantsBackendException extends RuntimeException {

    protected TradeImportsPlantsBackendException(String message) {
        super(message);
    }

    protected TradeImportsPlantsBackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
