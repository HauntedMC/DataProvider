package nl.hauntedmc.dataprovider.exception;

/** Failure in registration ownership, publication, or lookup state. */
public final class DataProviderRegistrationException extends DataProviderException {
    private static final long serialVersionUID = 1L;

    public DataProviderRegistrationException(String message, DataProviderFailureContext context, Throwable cause) {
        super(DataProviderErrorCode.REGISTRATION_FAILED, message, context, cause);
    }
}
