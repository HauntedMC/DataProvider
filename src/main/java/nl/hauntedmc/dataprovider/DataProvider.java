package nl.hauntedmc.dataprovider;

import nl.hauntedmc.dataprovider.database.DatabaseFactory;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.config.DatabaseConfigManager;
import nl.hauntedmc.dataprovider.config.MainConfigManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class DataProvider extends JavaPlugin {
    private static DataProvider instance;
    private MainConfigManager mainConfigManager;
    private DatabaseConfigManager databaseConfigManager;

    /**
     * Maps plugin names to their associated database connections.
     * Each plugin can have multiple database connections (e.g., MySQL, MongoDB).
     */
    private final Map<String, Map<DatabaseType, DatabaseProvider>> activeDatabases = new HashMap<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig(); // Ensures config.yml exists

        // Initialize Config Managers
        this.mainConfigManager = new MainConfigManager(this);
        this.databaseConfigManager = new DatabaseConfigManager(this);

        getLogger().info("DataProvider plugin has been enabled.");
    }

    @Override
    public void onDisable() {
        shutdownAllDatabases();
        getLogger().info("DataProvider plugin has been disabled.");
    }

    /**
     * Returns the singleton instance of DataProvider.
     */
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
        // Ensure this DatabaseType is enabled in config
        if (!mainConfigManager.isDatabaseTypeEnabled(databaseType)) {
            getLogger().warning("Database type " + databaseType.name() + " is disabled in config.yml.");
            return null;
        }

        // Ensure plugin's map exists
        activeDatabases.putIfAbsent(pluginName, new HashMap<>());

        // Check if the database is already registered
        if (activeDatabases.get(pluginName).containsKey(databaseType)) {
            getLogger().info(pluginName + " already has a connection to " + databaseType.name());
            return activeDatabases.get(pluginName).get(databaseType);
        }

        try {
            DatabaseProvider databaseProvider = DatabaseFactory.createDatabaseProvider(databaseType);
            databaseProvider.connect();
            activeDatabases.get(pluginName).put(databaseType, databaseProvider);
            getLogger().info(pluginName + " registered database: " + databaseType.name());
            return databaseProvider;
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
        return activeDatabases.getOrDefault(pluginName, new HashMap<>()).get(databaseType);
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
        for (String pluginName : activeDatabases.keySet()) {
            unregisterAllDatabases(pluginName);
        }
        activeDatabases.clear();
        getLogger().info("All database connections have been closed.");
    }
}
