package nl.hauntedmc.dataprovider.database;

import nl.hauntedmc.dataprovider.database.access.DataAccess;
import nl.hauntedmc.dataprovider.database.schema.SchemaManager;

/**
 * Represents a single database connection/provider.
 */
public interface DatabaseProvider {

    /**
     * Establish a connection to the database.
     */
    void connect();

    /**
     * Close the database connection.
     */
    void disconnect();

    /**
     * Check if the database is connected.
     */
    boolean isConnected();

    /**
     * Returns the SchemaManager to handle schema-level tasks (table creation, indexes, etc.).
     */
    SchemaManager getSchemaManager();

    /**
     * Returns the DataAccess for DML operations (CRUD queries, transactions, etc.).
     */
    DataAccess getDataAccess();
}
