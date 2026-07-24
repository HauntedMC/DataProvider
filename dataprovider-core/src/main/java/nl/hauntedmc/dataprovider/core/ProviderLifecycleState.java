package nl.hauntedmc.dataprovider.core;

/**
 * Internal local lifecycle states for a managed database provider.
 * Remote health is intentionally tracked separately.
 */
public enum ProviderLifecycleState {
    NEW,
    CONNECTING,
    READY,
    CLOSING,
    CLOSED,
    FAILED
}
