package nl.hauntedmc.dataprovider.exception;

/** Backend rejected configured authentication. */
public final class BackendAuthenticationException extends DataProviderException {
    private static final long serialVersionUID = 1L;

    public BackendAuthenticationException(String message, DataProviderFailureContext context, Throwable cause) {
        super(DataProviderErrorCode.AUTHENTICATION_FAILED, message, context, cause);
    }
}
