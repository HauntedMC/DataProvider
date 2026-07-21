package nl.hauntedmc.dataprovider.core;

import nl.hauntedmc.dataprovider.database.DatabaseProvider;

/**
 * Internal provider contract that includes lifecycle ownership.
 */
public interface ManagedDatabaseProvider extends DatabaseProvider {

    /** Returns local lifecycle state only and must not perform network I/O. */
    default boolean isLocallyConnected() {
        return isConnected();
    }

    /** Performs an explicit remote health probe; callers must invoke it asynchronously. */
    default boolean probeRemoteHealth() {
        return isConnected();
    }

    /**
     * Returns the most recent retained local lifecycle failure, when the provider exposes one.
     * Registry-level lifecycle failures are retained independently.
     */
    default Throwable lifecycleFailure() {
        return null;
    }

    /**
     * Establish a connection to the database.
     */
    void connect();

    /**
     * Close the database connection.
     */
    void disconnect();
}
