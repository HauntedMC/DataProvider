package nl.hauntedmc.dataprovider.exception;

/** Failure in registration ownership, publication, or lookup state. */
public final class DataProviderRegistrationException extends DataProviderException {
    public DataProviderRegistrationException(String message, DataProviderFailureContext context, Throwable cause) {
        super(DataProviderErrorCode.REGISTRATION_FAILED, message, context, cause);
    }
}
