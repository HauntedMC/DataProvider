package nl.hauntedmc.dataprovider.exception;

/** Backend operation failed without matching a more specific public failure category. */
public final class DataProviderOperationException extends DataProviderException {

    public DataProviderOperationException(
            String message,
            DataProviderFailureContext context,
            Throwable cause
    ) {
        super(DataProviderErrorCode.OPERATION_FAILED, message, context, cause);
    }
}
