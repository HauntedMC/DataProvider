package nl.hauntedmc.dataprovider.core.identity;

/** Lifecycle state for one platform plugin identity generation. */
public enum PluginIdentityState {
    /** Normal plugin operation is permitted. */
    ACTIVE,
    /** New work is rejected, while idempotent teardown remains permitted. */
    DISABLING,
    /** The identity generation is no longer valid for any operation. */
    INACTIVE;

    /** Returns whether teardown operations may still use the identity. */
    public boolean permitsCleanup() {
        return this == ACTIVE || this == DISABLING;
    }
}
