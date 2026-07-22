package nl.hauntedmc.dataprovider.exception;

/** Backend is disabled, unreachable, or otherwise unavailable. */
public final class BackendUnavailableException extends DataProviderException {
    public BackendUnavailableException(DataProviderErrorCode code, String message,
                                       DataProviderFailureContext context, Throwable cause) {
        super(code, message, context, cause);
        if (code != DataProviderErrorCode.BACKEND_DISABLED
                && code != DataProviderErrorCode.BACKEND_UNAVAILABLE) {
            throw new IllegalArgumentException("Unsupported backend availability error code: " + code);
        }
    }
}
