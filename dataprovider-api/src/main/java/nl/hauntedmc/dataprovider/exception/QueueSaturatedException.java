package nl.hauntedmc.dataprovider.exception;

/** Shared execution capacity rejected an operation before it started. */
public final class QueueSaturatedException extends DataProviderException {
    public QueueSaturatedException(String message, DataProviderFailureContext context, Throwable cause) {
        super(DataProviderErrorCode.QUEUE_SATURATED, message, context, cause);
    }
}
