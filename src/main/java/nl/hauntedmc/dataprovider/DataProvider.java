package nl.hauntedmc.dataprovider;

import nl.hauntedmc.dataprovider.config.MainConfigManager;
import nl.hauntedmc.dataprovider.database.DatabaseFactory;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.config.DatabaseConfigManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class DataProvider extends JavaPlugin {
    private static DataProvider instance;
    private MainConfigManager mainConfigManager;
    private DatabaseConfigManager databaseConfigManager;
    private final Map<String, DatabaseProvider> activeDatabases = new HashMap<>();

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
     *
     * @param pluginName    Name of the requesting plugin.
     * @param databaseType  Type of database (e.g. MYSQL, MONGODB).
     * @return DatabaseProvider instance
     */
    public DatabaseProvider registerDatabase(String pluginName, DatabaseType databaseType) {
        // Check if this DatabaseType is enabled
        if (!mainConfigManager.isDatabaseTypeEnabled(databaseType)) {
            getLogger().warning("Database type " + databaseType.name() + " is disabled in config.yml.");
            return null;
        }

        if (activeDatabases.containsKey(pluginName)) {
            getLogger().warning("Database already registered for: " + pluginName);
            return activeDatabases.get(pluginName);
        }

        try {
            DatabaseProvider databaseProvider = DatabaseFactory.createDatabaseProvider(databaseType);
            databaseProvider.connect();
            activeDatabases.put(pluginName, databaseProvider);
            getLogger().info("Registered database connection for " + pluginName + " using " + databaseType.name());
            return databaseProvider;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to register database for " + pluginName, e);
            return null;
        }
    }

    /**
     * Retrieves the active database connection for a specific plugin.
     *
     * @param pluginName Name of the requesting plugin.
     * @return DatabaseProvider instance
     */
    public DatabaseProvider getDatabase(String pluginName) {
        return activeDatabases.get(pluginName);
    }

    /**
     * Unregisters a database connection for a plugin.
     *
     * @param pluginName Name of the plugin.
     */
    public void unregisterDatabase(String pluginName) {
        DatabaseProvider provider = activeDatabases.remove(pluginName);
        if (provider != null) {
            provider.disconnect();
            getLogger().info("Unregistered database connection for " + pluginName);
        }
    }

    /**
     * Closes all active database connections when the plugin is disabled.
     */
    private void shutdownAllDatabases() {
        for (String pluginName : activeDatabases.keySet()) {
            unregisterDatabase(pluginName);
        }
        activeDatabases.clear();
        getLogger().info("All database connections have been closed.");
    }
}
