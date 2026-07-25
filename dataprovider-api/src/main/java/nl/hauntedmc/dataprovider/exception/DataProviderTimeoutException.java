package nl.hauntedmc.dataprovider.exception;

/** Operation exceeded a configured or backend timeout. */
public final class DataProviderTimeoutException extends DataProviderException {
    private static final long serialVersionUID = 1L;

    public DataProviderTimeoutException(String message, DataProviderFailureContext context, Throwable cause) {
        super(DataProviderErrorCode.OPERATION_TIMED_OUT, message, context, cause);
    }
}
