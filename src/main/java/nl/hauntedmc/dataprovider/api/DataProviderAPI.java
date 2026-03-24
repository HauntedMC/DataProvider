package nl.hauntedmc.dataprovider.api;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;

import java.util.Objects;

/**
 * DataProviderAPI is the public facade that exposes only the safe methods for
 * third-party plugins. Internally, it delegates to a DataProviderHandler, but it does not
 * expose sensitive methods (like shutdownAllDatabases or getActiveDatabases).
 */
public class DataProviderAPI {

    private final DataProviderHandler handler;

    /**
     * Constructs the API wrapper.
     *
     * @param handler the internal DataProviderHandler instance.
     */
    public DataProviderAPI(DataProviderHandler handler) {
        this.handler = Objects.requireNonNull(handler, "DataProviderHandler cannot be null");
    }

    /**
     * Registers a database connection for the resolved caller plugin.
     *
     * @param databaseType         the type of database (e.g. MYSQL, MONGODB, etc.)
     * @param connectionIdentifier a unique identifier for the connection
     * @return the registered {@link DatabaseProvider} instance.
     */
    public DatabaseProvider registerDatabase(DatabaseType databaseType, String connectionIdentifier) {
        return handler.registerDatabase(databaseType, connectionIdentifier);
    }

    /**
     * Unregisters a specific database connection for the resolved caller plugin.
     *
     * @param databaseType         the type of database.
     * @param connectionIdentifier the connection identifier.
     */
    public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        handler.unregisterDatabase(databaseType, connectionIdentifier);
    }

    /**
     * Unregisters all database connections for the resolved caller plugin.
     */
    public void unregisterAllDatabases() {
        handler.unregisterAllDatabases();
    }

    /**
     * Retrieves a registered database connection for the resolved caller plugin.
     *
     * @param databaseType         the type of database.
     * @param connectionIdentifier the connection identifier.
     * @return the {@link DatabaseProvider} instance, or null if not registered.
     */
    public DatabaseProvider getRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        return handler.getRegisteredDatabase(databaseType, connectionIdentifier);
    }
}
