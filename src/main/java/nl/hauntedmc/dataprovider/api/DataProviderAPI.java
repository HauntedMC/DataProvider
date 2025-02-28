package nl.hauntedmc.dataprovider.api;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;

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
        this.handler = handler;
    }

    /**
     * Authenticates the calling plugin using a secret token.
     *
     * @param pluginName the calling plugin (typically "this")
     * @param token  the secret token provided by the plugin
     * @return true if authentication is successful, false otherwise.
     */
    public boolean authenticate(String pluginName, String token) {
        return handler.authenticate(pluginName, token);
    }

    /**
     * Registers a database connection for the calling plugin.
     *
     * @param pluginName               the calling plugin (typically "this")
     * @param databaseType         the type of database (e.g. MYSQL, MONGODB, etc.)
     * @param connectionIdentifier a unique identifier for the connection
     * @return the registered {@link DatabaseProvider} instance.
     */
    public DatabaseProvider registerDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        return handler.registerDatabase(pluginName, databaseType, connectionIdentifier);
    }

    /**
     * Unregisters a specific database connection for the calling plugin.
     *
     * @param pluginName               the calling plugin (typically "this")
     * @param databaseType         the type of database.
     * @param connectionIdentifier the connection identifier.
     */
    public void unregisterDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        handler.unregisterDatabase(pluginName, databaseType, connectionIdentifier);
    }

    /**
     * Unregisters all database connections for the calling plugin.
     *
     * @param pluginName the calling plugin (typically "this")
     */
    public void unregisterAllDatabases(String pluginName) {
        handler.unregisterAllDatabases(pluginName);
    }

    /**
     * Retrieves a registered database connection for the calling plugin.
     *
     * @param pluginName               the calling plugin (typically "this")
     * @param databaseType         the type of database.
     * @param connectionIdentifier the connection identifier.
     * @return the {@link DatabaseProvider} instance, or null if not registered.
     */
    public DatabaseProvider getRegisteredDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        return handler.getRegisteredDatabase(pluginName, databaseType, connectionIdentifier);
    }
}
