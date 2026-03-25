package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.database.DatabaseProvider;

/**
 * Internal provider contract that includes lifecycle ownership.
 */
public interface ManagedDatabaseProvider extends DatabaseProvider {

    /**
     * Establish a connection to the database.
     */
    void connect();

    /**
     * Close the database connection.
     */
    void disconnect();
}
