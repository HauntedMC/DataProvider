package nl.hauntedmc.dataprovider.platform.bukkit.api;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;
import nl.hauntedmc.dataprovider.database.internal.DataProviderHandler;
import org.bukkit.plugin.java.JavaPlugin;

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
     * @param plugin the calling plugin (typically "this")
     * @param token  the secret token provided by the plugin
     * @return true if authentication is successful, false otherwise.
     */
    public boolean authenticate(JavaPlugin plugin, String token) {
        return handler.authenticate(plugin, token);
    }

    /**
     * Registers a database connection for the calling plugin.
     *
     * @param plugin               the calling plugin (typically "this")
     * @param databaseType         the type of database (e.g. MYSQL, MONGODB, etc.)
     * @param connectionIdentifier a unique identifier for the connection
     * @return the registered {@link BaseDatabaseProvider} instance.
     */
    public BaseDatabaseProvider registerDatabase(JavaPlugin plugin, DatabaseType databaseType, String connectionIdentifier) {
        return handler.registerDatabase(plugin, databaseType, connectionIdentifier);
    }

    /**
     * Unregisters a specific database connection for the calling plugin.
     *
     * @param plugin               the calling plugin (typically "this")
     * @param databaseType         the type of database.
     * @param connectionIdentifier the connection identifier.
     */
    public void unregisterDatabase(JavaPlugin plugin, DatabaseType databaseType, String connectionIdentifier) {
        handler.unregisterDatabase(plugin, databaseType, connectionIdentifier);
    }

    /**
     * Unregisters all database connections for the calling plugin.
     *
     * @param plugin the calling plugin (typically "this")
     */
    public void unregisterAllDatabases(JavaPlugin plugin) {
        handler.unregisterAllDatabases(plugin);
    }

    /**
     * Retrieves a registered database connection for the calling plugin.
     *
     * @param plugin               the calling plugin (typically "this")
     * @param databaseType         the type of database.
     * @param connectionIdentifier the connection identifier.
     * @return the {@link BaseDatabaseProvider} instance, or null if not registered.
     */
    public BaseDatabaseProvider getRegisteredDatabase(JavaPlugin plugin, DatabaseType databaseType, String connectionIdentifier) {
        return handler.getRegisteredDatabase(plugin, databaseType, connectionIdentifier);
    }
}
