package nl.hauntedmc.dataprovider.exception;

/** A uniqueness, optimistic-locking, or compare-and-set conflict occurred. */
public final class DataConflictException extends DataProviderException {
    private static final long serialVersionUID = 1L;

    public DataConflictException(String message, DataProviderFailureContext context, Throwable cause) {
        super(DataProviderErrorCode.CONFLICT, message, context, cause);
    }
}
