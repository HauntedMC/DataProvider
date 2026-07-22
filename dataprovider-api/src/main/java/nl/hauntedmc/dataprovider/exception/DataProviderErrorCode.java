package nl.hauntedmc.dataprovider.exception;

/** Stable machine-readable failure codes exposed by the DataProvider API. */
public enum DataProviderErrorCode {
    CONFIGURATION_INVALID,
    CONFIGURATION_MISSING,
    REGISTRATION_FAILED,
    BACKEND_DISABLED,
    BACKEND_UNAVAILABLE,
    AUTHENTICATION_FAILED,
    OPERATION_FAILED,
    OPERATION_TIMED_OUT,
    QUEUE_SATURATED,
    SERIALIZATION_FAILED,
    CONFLICT,
    TRANSACTION_FAILED,
    PROVIDER_CLOSED
}
