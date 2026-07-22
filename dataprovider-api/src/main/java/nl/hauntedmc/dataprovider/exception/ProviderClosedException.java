package nl.hauntedmc.dataprovider.exception;

/** A runtime-scoped provider or registration scope is already closed. */
public final class ProviderClosedException extends DataProviderException {
    public ProviderClosedException(String message, DataProviderFailureContext context, Throwable cause) {
        super(DataProviderErrorCode.PROVIDER_CLOSED, message, context, cause);
    }
}
