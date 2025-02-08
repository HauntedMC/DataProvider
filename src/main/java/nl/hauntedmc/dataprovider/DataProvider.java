package nl.hauntedmc.dataprovider;

import nl.hauntedmc.dataprovider.config.MainConfigManager;
import nl.hauntedmc.dataprovider.command.DataProviderCommand;
import nl.hauntedmc.dataprovider.database.DatabaseConfigManager;
import nl.hauntedmc.dataprovider.database.DatabaseFactory;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

public class DataProvider extends JavaPlugin {

    private static DataProvider instance;
    private MainConfigManager mainConfigManager;
    private DatabaseConfigManager databaseConfigManager;

    /**
     * Maps plugin names to their associated database connections.
     * Each plugin can have multiple database types (e.g., MySQL, MongoDB).
     *
     * Using the BaseDatabaseProvider interface allows us to store both
     * relational (e.g. MySQL) and non-relational (e.g. Redis, MongoDB)
     * implementations under one map.
     */
    private final ConcurrentMap<String, ConcurrentMap<DatabaseType, BaseDatabaseProvider>> activeDatabases
            = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig(); // ensure config.yml exists

        this.mainConfigManager = new MainConfigManager(this);
        this.databaseConfigManager = new DatabaseConfigManager(this);

        getLogger().info("[DataProvider] Enabled (v" + getDescription().getVersion() + ").");

        // Register the /dataprovider command and its tab completer
        DataProviderCommand commandExecutor = new DataProviderCommand();
        Objects.requireNonNull(getCommand("dataprovider")).setExecutor(commandExecutor);
        Objects.requireNonNull(getCommand("dataprovider")).setTabCompleter(commandExecutor);
    }

    @Override
    public void onDisable() {
        shutdownAllDatabases();
        getLogger().info("[DataProvider] Disabled.");
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
     * Returns the active Databases.
     */
    public ConcurrentMap<String, ConcurrentMap<DatabaseType, BaseDatabaseProvider>> getActiveDatabases() {
        return activeDatabases;
    }

    /**
     * Registers (and creates a connection to) a database for a specific plugin.
     *
     * @param pluginName   The name of the requesting plugin.
     * @param databaseType The type of database to register.
     * @return BaseDatabaseProvider instance, or null if disabled or connection fails.
     */
    public BaseDatabaseProvider registerDatabase(String pluginName, DatabaseType databaseType) {
        // Check if this DatabaseType is enabled in config
        if (!mainConfigManager.isDatabaseTypeEnabled(databaseType)) {
            getLogger().warning("Database type " + databaseType.name() + " is disabled in config.yml.");
            return null;
        }

        // Ensure the map for pluginName exists
        activeDatabases.putIfAbsent(pluginName, new ConcurrentHashMap<>());

        ConcurrentMap<DatabaseType, BaseDatabaseProvider> pluginDatabases = activeDatabases.get(pluginName);

        // If already registered, return the existing provider
        if (pluginDatabases.containsKey(databaseType)) {
            getLogger().info(pluginName + " already has a connection to " + databaseType.name());
            return pluginDatabases.get(databaseType);
        }

        try {
            // Create the provider from our factory
            BaseDatabaseProvider databaseProvider = DatabaseFactory.createDatabaseProvider(databaseType);
            databaseProvider.connect();

            if (!databaseProvider.isConnected()) {
                getLogger().severe("Failed to establish connection for " + pluginName + " with " + databaseType.name());
                return null;
            }

            // Store it in the map
            pluginDatabases.put(databaseType, databaseProvider);
            getLogger().info(pluginName + " registered database: " + databaseType.name());
            return databaseProvider;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to register database for " + pluginName, e);
            return null;
        }
    }

    /**
     * Retrieves an active database connection for a plugin.
     *
     * @param pluginName   The plugin that owns the connection
     * @param databaseType The type of database (MySQL, MongoDB, etc.)
     * @return The BaseDatabaseProvider if present, otherwise null
     */
    public BaseDatabaseProvider getDatabase(String pluginName, DatabaseType databaseType) {
        return activeDatabases
                .getOrDefault(pluginName, new ConcurrentHashMap<>())
                .get(databaseType);
    }

    /**
     * Unregisters (and disconnects) a specific database for a plugin.
     */
    public void unregisterDatabase(String pluginName, DatabaseType databaseType) {
        ConcurrentMap<DatabaseType, BaseDatabaseProvider> pluginDatabases = activeDatabases.get(pluginName);
        if (pluginDatabases != null) {
            BaseDatabaseProvider provider = pluginDatabases.remove(databaseType);
            if (provider != null) {
                provider.disconnect();
                getLogger().info(pluginName + " unregistered database: " + databaseType.name());
            }
            if (pluginDatabases.isEmpty()) {
                activeDatabases.remove(pluginName);
            }
        }
    }

    /**
     * Unregisters (and disconnects) all databases for a given plugin.
     */
    public void unregisterAllDatabases(String pluginName) {
        if (activeDatabases.containsKey(pluginName)) {
            for (DatabaseType type : activeDatabases.get(pluginName).keySet()) {
                unregisterDatabase(pluginName, type);
            }
        }
    }

    /**
     * Closes all active database connections.
     */
    private void shutdownAllDatabases() {
        for (Map.Entry<String, ConcurrentMap<DatabaseType, BaseDatabaseProvider>> entry : activeDatabases.entrySet()) {
            String pluginName = entry.getKey();
            for (Map.Entry<DatabaseType, BaseDatabaseProvider> dbEntry : entry.getValue().entrySet()) {
                try {
                    dbEntry.getValue().disconnect();
                } catch (Exception e) {
                    getLogger().log(Level.SEVERE,
                            "Error disconnecting database " + dbEntry.getKey() + " for plugin " + pluginName, e);
                }
            }
        }
        activeDatabases.clear();
        getLogger().info("All database connections have been closed.");
    }
}
