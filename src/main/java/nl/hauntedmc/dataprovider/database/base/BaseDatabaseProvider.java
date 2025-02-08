package nl.hauntedmc.dataprovider.database.base;

/**
 * The minimal shared parent for all database providers (relational or NoSQL).
 * Allows your plugin to store them all in a common map.
 *
 * You may add more common methods (like debug logging) here if you wish.
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
     * Relational providers will return a RelationalDataAccess,
     * NoSQL providers will return a NoSQLDataAccess, etc.
     */
    BaseDataAccess getDataAccess();
}
