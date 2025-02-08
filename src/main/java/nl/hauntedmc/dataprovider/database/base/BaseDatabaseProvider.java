package nl.hauntedmc.dataprovider.database.base;

/**
 * The minimal shared parent for all database providers (relational or NoSQL).
 * Allows storing them in a common map.
 */
public interface BaseDatabaseProvider {

    /**
     * Establish a connection to the database.
     */
    void connect();

    /**
     * Close the database connection.
     */
    void disconnect();

    /**
     * Check if the database is currently connected.
     *
     * @return true if connected, false otherwise.
     */
    boolean isConnected();

    /**
     * Returns a BaseDataAccess object for this database.
     *
     * @return the data access object
     */
    BaseDataAccess getDataAccess();
}
