package nl.hauntedmc.dataprovider.exception;

/** The calling plugin is not permitted to use the requested configured connection. */
public final class DataProviderAccessDeniedException extends DataProviderException {
    private static final long serialVersionUID = 1L;

    public DataProviderAccessDeniedException(String message, DataProviderFailureContext context, Throwable cause) {
        super(DataProviderErrorCode.ACCESS_DENIED, message, context, cause);
    }
}
