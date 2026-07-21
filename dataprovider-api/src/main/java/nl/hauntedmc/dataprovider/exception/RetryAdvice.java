package nl.hauntedmc.dataprovider.exception;

/** Guidance for callers considering a retry. */
public enum RetryAdvice {
    NEVER,
    SAFE,
    CONDITIONAL
}
