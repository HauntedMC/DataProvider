package nl.hauntedmc.dataprovider.exception;

/** Backend rejected configured authentication. */
public final class BackendAuthenticationException extends DataProviderException {
    public BackendAuthenticationException(String message, DataProviderFailureContext context, Throwable cause) {
        super(DataProviderErrorCode.AUTHENTICATION_FAILED, message, context, cause);
    }
}
