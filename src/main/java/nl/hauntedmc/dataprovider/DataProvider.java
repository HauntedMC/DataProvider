package nl.hauntedmc.dataprovider;

import nl.hauntedmc.dataprovider.database.DatabaseFactory;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.config.DatabaseConfigManager;
import nl.hauntedmc.dataprovider.config.MainConfigManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

public class DataProvider extends JavaPlugin {
    private static DataProvider instance;
    private MainConfigManager mainConfigManager;
    private DatabaseConfigManager databaseConfigManager;

    private final ConcurrentMap<String, ConcurrentMap<DatabaseType, DatabaseProvider>> activeDatabases
            = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.mainConfigManager = new MainConfigManager(this);
        this.databaseConfigManager = new DatabaseConfigManager(this);

        getLogger().info("[DataProvider] Plugin enabled. Version: " + getDescription().getVersion());
    }

    @Override
    public void onDisable() {
        shutdownAllDatabases();
        getLogger().info("[DataProvider] Plugin disabled.");
    }

    public static DataProvider getInstance() {
        return instance;
    }

    /**
     * Returns the MainConfigManager instance.
     */
    public MainConfigManager getMainConfigManager() {
        return mainConfigManager;
    }

    /**
     * Returns the DatabaseConfigManager instance.
     */
    public DatabaseConfigManager getDatabaseConfigManager() {
        return databaseConfigManager;
    }

    /**
     * Registers a new database connection for a specific plugin.
     * Allows plugins to use multiple database types (e.g., MySQL & MongoDB).
     *
     * @param pluginName    The name of the requesting plugin.
     * @param databaseType  The type of database to register.
     * @return DatabaseProvider instance, or null if the database type is disabled or connection fails.
     */
    public DatabaseProvider registerDatabase(String pluginName, DatabaseType databaseType) {
        // Ensure DatabaseType is enabled
        if (!mainConfigManager.isDatabaseTypeEnabled(databaseType)) {
            getLogger().warning("Database type " + databaseType.name() + " is disabled in config.yml.");
            return null;
        }
        activeDatabases.putIfAbsent(pluginName, new ConcurrentHashMap<>());

        // Return existing if already registered
        Map<DatabaseType, DatabaseProvider> pluginDatabases = activeDatabases.get(pluginName);
        if (pluginDatabases.containsKey(databaseType)) {
            return pluginDatabases.get(databaseType);
        }

        // Create and connect
        try {
            DatabaseProvider dbProvider = DatabaseFactory.createDatabaseProvider(databaseType);
            dbProvider.connect();

            if (!dbProvider.isConnected()) {
                getLogger().severe("Failed to establish connection for " + pluginName + " with " + databaseType.name());
                return null;
            }

            pluginDatabases.put(databaseType, dbProvider);
            getLogger().info(pluginName + " registered database: " + databaseType.name());
            return dbProvider;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to register database for " + pluginName, e);
            return null;
        }
    }

    /**
     * Retrieves an active database connection for a specific plugin.
     *
     * @param pluginName    Name of the plugin.
     * @param databaseType  Type of the database to retrieve.
     * @return DatabaseProvider instance or null if not found.
     */
    public DatabaseProvider getDatabase(String pluginName, DatabaseType databaseType) {
        return activeDatabases.getOrDefault(pluginName, new ConcurrentHashMap<>()).get(databaseType);
    }

    /**
     * Unregisters a specific database connection for a plugin.
     *
     * @param pluginName    Name of the plugin.
     * @param databaseType  Type of the database to unregister.
     */
    public void unregisterDatabase(String pluginName, DatabaseType databaseType) {
        Map<DatabaseType, DatabaseProvider> pluginDatabases = activeDatabases.get(pluginName);
        if (pluginDatabases != null && pluginDatabases.containsKey(databaseType)) {
            DatabaseProvider provider = pluginDatabases.remove(databaseType);
            provider.disconnect();
            getLogger().info(pluginName + " unregistered database: " + databaseType.name());

            // If no more databases are in use, remove the plugin entry
            if (pluginDatabases.isEmpty()) {
                activeDatabases.remove(pluginName);
            }
        }
    }

    /**
     * Unregisters all databases for a given plugin.
     *
     * @param pluginName Name of the plugin.
     */
    public void unregisterAllDatabases(String pluginName) {
        if (activeDatabases.containsKey(pluginName)) {
            for (DatabaseType type : activeDatabases.get(pluginName).keySet()) {
                unregisterDatabase(pluginName, type);
            }
        }
    }

    /**
     * Closes all active database connections when the plugin is disabled.
     */
    private void shutdownAllDatabases() {
        for (Map.Entry<String, ConcurrentMap<DatabaseType, DatabaseProvider>> entry : activeDatabases.entrySet()) {
            for (Map.Entry<DatabaseType, DatabaseProvider> dbEntry : entry.getValue().entrySet()) {
                try {
                    dbEntry.getValue().disconnect();
                } catch (Exception e) {
                    getLogger().log(Level.SEVERE, "Error disconnecting " + dbEntry.getKey() + " for plugin " + entry.getKey(), e);
                }
            }
        }
        activeDatabases.clear();
        getLogger().info("All database connections have been closed.");
    }
}
