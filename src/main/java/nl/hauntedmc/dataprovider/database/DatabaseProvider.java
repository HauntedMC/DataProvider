package nl.hauntedmc.dataprovider.database;

import nl.hauntedmc.dataprovider.database.access.DataAccess;
import nl.hauntedmc.dataprovider.database.schema.SchemaManager;

public interface DatabaseProvider {
    /**
     * Establishes a connection to the database.
     */
    void connect();

    /**
     * Closes the database connection.
     */
    void disconnect();

    /**
     * Checks if the database is currently connected.
     *
     * @return true if connected, false otherwise.
     */
    boolean isConnected();

    /**
     * Retrieves the SchemaManager for this database.
     * Used for creating and modifying database structures (tables, indexes, etc.).
     *
     * @return SchemaManager instance.
     */
    SchemaManager getSchemaManager();

    /**
     * Retrieves the DataAccess for this database.
     * Used for querying and updating data within the database.
     *
     * @return DataAccess instance.
     */
    DataAccess getDataAccess();
}
