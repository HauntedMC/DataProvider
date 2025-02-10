package nl.hauntedmc.dataprovider.database.internal;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.config.MainConfigManager;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.config.DatabaseConfigManager;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;
import nl.hauntedmc.dataprovider.logger.DPLogger;
import nl.hauntedmc.dataprovider.security.DataProviderSecurityManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentMap;

/**
 * DataProviderHandler is the single public entry point for database‐related operations.
 * It creates the internal configuration manager and registry and exposes methods
 * (such as registering/unregistering connections) that perform runtime security checks.
 */
public class DataProviderHandler {

    private final MainConfigManager configManager;
    private final DataProviderRegistry registry;
    private final DatabaseConfigManager databaseConfigManager;

    /**
     * Public constructor.
     *
     * @param plugin the main DataProvider plugin instance.
     */
    public DataProviderHandler(DataProvider plugin) {
        this.configManager = new MainConfigManager(plugin);
        this.databaseConfigManager = new DatabaseConfigManager(plugin);
        this.registry = new DataProviderRegistry();
    }

    protected MainConfigManager getConfigManager() {
        return configManager;
    }

    protected DatabaseConfigManager getDatabaseConfigManager() {
        return databaseConfigManager;
    }

    /**
     * Authenticates the calling plugin using the secret token.
     *
     * @param plugin the calling JavaPlugin instance (typically “this”)
     * @param token  the secret token the plugin provides
     * @return true if authentication is successful, false otherwise.
     */
    public boolean authenticate(JavaPlugin plugin, String token) {
        JavaPlugin genuine = JavaPlugin.getProvidingPlugin(plugin.getClass());
        String pluginName = genuine.getName();
        return DataProviderSecurityManager.authorize(pluginName, token);
    }


    /**
     * Registers a database connection.
     *
     * @param plugin               the calling JavaPlugin instance (typically “this”)
     * @param databaseType         the type of database (e.g. MYSQL, MONGODB, etc.)
     * @param connectionIdentifier a unique identifier for the connection
     * @return the registered BaseDatabaseProvider instance
     * @throws SecurityException if the caller’s identity can’t be verified
     */
    public BaseDatabaseProvider registerDatabase(JavaPlugin plugin, DatabaseType databaseType, String connectionIdentifier) {
        try {
            isPluginAuthorized(plugin);
            String pluginName = getPluginIdentifier(plugin);
            return registry.registerDatabase(pluginName, databaseType, connectionIdentifier);
        } catch (Exception e){
            throw new IllegalStateException("Could not register database: " + e);
        }
    }

    /**
     * Unregisters a specific database connection.
     *
     * @param plugin               the calling JavaPlugin instance
     * @param databaseType         the type of database
     * @param connectionIdentifier the connection identifier
     * @throws SecurityException if the caller’s identity cannot be verified
     */
    public void unregisterDatabase(JavaPlugin plugin, DatabaseType databaseType, String connectionIdentifier) {
        try {
            isPluginAuthorized(plugin);
            String pluginName = getPluginIdentifier(plugin);
            registry.unregisterDatabase(pluginName, databaseType, connectionIdentifier);
        } catch (Exception e){
            throw new IllegalStateException("Could not register database: " + e);
        }
    }

    /**
     * Unregisters all database connections for the given plugin.
     *
     * @param plugin the calling JavaPlugin instance
     * @throws SecurityException if the caller’s identity cannot be verified
     */
    public void unregisterAllDatabases(JavaPlugin plugin) {
        try {
            isPluginAuthorized(plugin);
            String pluginName = getPluginIdentifier(plugin);
            registry.unregisterAllDatabases(pluginName);
        } catch (Exception e){
            throw new IllegalStateException("Could not register database: " + e);
        }
    }

    /**
     * Shuts down all active database connections.
     */
    public void shutdownAllDatabases() {
        registry.shutdownAllDatabases();
    }

    /**
     * Retrieves a registered database connection.
     *
     * @param plugin               the calling JavaPlugin instance
     * @param databaseType         the type of database
     * @param connectionIdentifier the connection identifier
     * @return the BaseDatabaseProvider instance, or null if not registered
     * @throws SecurityException if the caller’s identity cannot be verified
     */
    public BaseDatabaseProvider getRegisteredDatabase(JavaPlugin plugin, DatabaseType databaseType, String connectionIdentifier) {
        try {
            isPluginAuthorized(plugin);
            String pluginName = getPluginIdentifier(plugin);
            return registry.getDatabase(pluginName, databaseType, connectionIdentifier);
        } catch (Exception e){
            throw new IllegalStateException("Could not register database: " + e);
        }
    }

    private @NotNull String getPluginIdentifier(JavaPlugin plugin) {
        JavaPlugin genuine = JavaPlugin.getProvidingPlugin(plugin.getClass());
        return genuine.getName();
    }

    private void isPluginAuthorized(JavaPlugin plugin) {
        JavaPlugin genuine = JavaPlugin.getProvidingPlugin(plugin.getClass());
        String pluginName = genuine.getName();
        if (Bukkit.getPluginManager().getPlugin(pluginName) == null) {
            DPLogger.error("Plugin " + pluginName + " is not registered with Bukkit.");
            throw new SecurityException("Plugin " + pluginName + " is not registered with Bukkit.");
        }
        if (plugin.getClass().getClassLoader() != genuine.getClass().getClassLoader()) {
            DPLogger.error("Class loader mismatch for plugin " + pluginName);
            throw new SecurityException("Class loader mismatch for plugin " + pluginName);
        }
        if (!DataProviderSecurityManager.isAuthorized(pluginName)) {
            DPLogger.error("Plugin " + pluginName + " is not authorized. Please authenticate first.");
            throw new SecurityException("Plugin " + pluginName + " is not authorized. Please authenticate first.");
        }
    }

    /**
     * Returns a view of the active database connections.
     * Note: This method is provided for debugging/administrative purposes.
     *
     * @return a ConcurrentMap of active connections
     */
    public ConcurrentMap<DatabaseConnectionKey, BaseDatabaseProvider> getActiveDatabases() {
        return registry.getActiveDatabases();
    }
}
