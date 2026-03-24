package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;

import java.util.Objects;
import java.util.concurrent.ConcurrentMap;

/**
 * DataProviderHandler is the single public entry point for database‐related operations.
 * It creates the internal configuration manager and registry and exposes methods
 * (such as registering/unregistering connections) that perform runtime security checks.
 */
public class DataProviderHandler {

    private final DataProviderRegistry registry;
    private final SecurityManager securityManager;

    /**
     * Public constructor.
     *
     */
    public DataProviderHandler() {
        securityManager = new SecurityManager();
        DatabaseConfigMap configMap = new DatabaseConfigMap();
        DatabaseFactory factory = new DatabaseFactory(configMap);
        this.registry = new DataProviderRegistry(factory);
    }

    /**
     * Authenticates the calling plugin using the secret token.
     *
     * @param pluginName the calling JavaPlugin instance (typically “this”)
     * @param token  the secret token provided by the plugin.
     * @return {@code true} if authentication is successful; {@code false} otherwise.
     * @throws SecurityException if the provided plugin instance is not registered.
     */
    public boolean authenticate(String pluginName, String token) {
        requirePluginName(pluginName);
        return securityManager.authorize(pluginName, token);
    }

    /**
     * Registers a database connection for the calling plugin.
     *
     * @param pluginName               the calling JavaPlugin instance (typically “this”)
     * @param databaseType         the type of database (e.g. MYSQL, MONGODB, etc.)
     * @param connectionIdentifier a unique identifier for the connection.
     * @return the registered {@link DatabaseProvider} instance.
     * @throws SecurityException if the plugin is not registered or not authorized.
     */
    public DatabaseProvider registerDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        requirePluginName(pluginName);
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        requireConnectionIdentifier(connectionIdentifier);
        authorizationCheck(pluginName);
        return registry.registerDatabase(pluginName, databaseType, connectionIdentifier);
    }

    /**
     * Unregisters a specific database connection for the calling plugin.
     *
     * @param pluginName               the calling JavaPlugin instance.
     * @param databaseType         the type of database.
     * @param connectionIdentifier the connection identifier.
     * @throws SecurityException if the plugin is not registered or not authorized.
     */
    public void unregisterDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        requirePluginName(pluginName);
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        requireConnectionIdentifier(connectionIdentifier);
        authorizationCheck(pluginName);
        registry.unregisterDatabase(pluginName, databaseType, connectionIdentifier);
    }

    /**
     * Unregisters all database connections for the calling plugin.
     *
     * @param pluginName the calling JavaPlugin instance.
     * @throws SecurityException if the plugin is not registered or not authorized.
     */
    public void unregisterAllDatabases(String pluginName) {
        requirePluginName(pluginName);
        authorizationCheck(pluginName);
        registry.unregisterAllDatabases(pluginName);
        securityManager.revokeAuthorization(pluginName);
    }

    /**
     * Shuts down all active database connections.
     */
    public void shutdownAllDatabases() {
        registry.shutdownAllDatabases();
    }

    /**
     * Retrieves a registered database connection for the calling plugin.
     *
     * @param pluginName               the calling JavaPlugin instance.
     * @param databaseType         the type of database.
     * @param connectionIdentifier the connection identifier.
     * @return the {@link DatabaseProvider} instance, or {@code null} if not registered.
     * @throws SecurityException if the plugin is not registered or not authorized.
     */
    public DatabaseProvider getRegisteredDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        requirePluginName(pluginName);
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        requireConnectionIdentifier(connectionIdentifier);
        authorizationCheck(pluginName);
        return registry.getDatabase(pluginName, databaseType, connectionIdentifier);
    }

    /**
     * Returns a snapshot of active database connections.
     * <p>
     * Note: This method is provided for debugging/administrative purposes.
     * </p>
     *
     * @return a {@link ConcurrentMap} of active connections.
     */
    public ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> getActiveDatabases() {
        return registry.getActiveDatabases();
    }

    private void authorizationCheck(String pluginName) {
        if (!securityManager.isAuthorized(pluginName)) {
            DataProvider.getLogger().error("Plugin " + pluginName + " is not authorized. Please authenticate first.");
            throw new SecurityException("Plugin " + pluginName + " is not authorized. Please authenticate first.");
        }
    }

    private static void requirePluginName(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) {
            throw new IllegalArgumentException("Plugin name cannot be null or blank.");
        }
    }

    private static void requireConnectionIdentifier(String connectionIdentifier) {
        if (connectionIdentifier == null || connectionIdentifier.isBlank()) {
            throw new IllegalArgumentException("Connection identifier cannot be null or blank.");
        }
    }

}
