package nl.hauntedmc.dataprovider.exception;

/** Invalid or missing DataProvider configuration. */
public final class DataProviderConfigurationException extends DataProviderException {
    public DataProviderConfigurationException(DataProviderErrorCode code, String message,
                                              DataProviderFailureContext context, Throwable cause) {
        super(code, message, context, cause);
        if (code != DataProviderErrorCode.CONFIGURATION_INVALID
                && code != DataProviderErrorCode.CONFIGURATION_MISSING) {
            throw new IllegalArgumentException("Unsupported configuration error code: " + code);
        }
    }
}
