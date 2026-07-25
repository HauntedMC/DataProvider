package nl.hauntedmc.dataprovider.exception;

/** Data could not be serialized or deserialized safely. */
public final class DataSerializationException extends DataProviderException {
    private static final long serialVersionUID = 1L;

    public DataSerializationException(String message, DataProviderFailureContext context, Throwable cause) {
        super(DataProviderErrorCode.SERIALIZATION_FAILED, message, context, cause);
    }
}
