package nl.hauntedmc.dataprovider.registry;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.DatabaseFactory;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

/**
 * Wraps the mapping of plugin names to their database connections and provides
 * a clean API for registering, retrieving, and unregistering connections.
 */
public class DataProviderRegistry {


    private final ConcurrentMap<String, ConcurrentMap<DatabaseType, BaseDatabaseProvider>> activeDatabases = new ConcurrentHashMap<>();

    /**
     * Registers (and creates a connection to) a database for a specific plugin.
     *
     * @param pluginName   The name of the requesting plugin.
     * @param databaseType The type of database to register.
     * @return the registered BaseDatabaseProvider, or null if disabled or connection fails.
     */
    public BaseDatabaseProvider registerDatabase(String pluginName, DatabaseType databaseType) {
        // Check if this DatabaseType is enabled via the main config
        if (!DataProvider.getInstance().getMainConfigManager().isDatabaseTypeEnabled(databaseType)) {
            DataProvider.getInstance().getLogger().warning("Database type " + databaseType.name() + " is disabled in config.yml.");
            return null;
        }

        // Ensure the map for the plugin exists
        activeDatabases.putIfAbsent(pluginName, new ConcurrentHashMap<>());
        ConcurrentMap<DatabaseType, BaseDatabaseProvider> pluginDatabases = activeDatabases.get(pluginName);

        // If already registered, return the existing provider
        if (pluginDatabases.containsKey(databaseType)) {
            DataProvider.getInstance().getLogger().info(pluginName + " already has a connection to " + databaseType.name());
            return pluginDatabases.get(databaseType);
        }

        try {
            // Create the provider from our factory and connect it
            BaseDatabaseProvider databaseProvider = DatabaseFactory.createDatabaseProvider(databaseType);
            databaseProvider.connect();

            if (!databaseProvider.isConnected()) {
                DataProvider.getInstance().getLogger().severe("Failed to establish connection for " + pluginName + " with " + databaseType.name());
                return null;
            }

            // Store and return the provider
            pluginDatabases.put(databaseType, databaseProvider);
            DataProvider.getInstance().getLogger().info(pluginName + " registered database: " + databaseType.name());
            return databaseProvider;
        } catch (Exception e) {
            DataProvider.getInstance().getLogger().log(Level.SEVERE, "Failed to register database for " + pluginName, e);
            return null;
        }
    }

    /**
     * Retrieves an active database connection for a plugin.
     *
     * @param pluginName   The plugin that owns the connection.
     * @param databaseType The type of database (e.g. MYSQL, MONGODB, etc.)
     * @return the BaseDatabaseProvider if present, otherwise null.
     */
    public BaseDatabaseProvider getDatabase(String pluginName, DatabaseType databaseType) {
        return activeDatabases
                .getOrDefault(pluginName, new ConcurrentHashMap<>())
                .get(databaseType);
    }

    /**
     * Unregisters (and disconnects) a specific database for a plugin.
     *
     * @param pluginName   The plugin name.
     * @param databaseType The type of database.
     */
    public void unregisterDatabase(String pluginName, DatabaseType databaseType) {
        ConcurrentMap<DatabaseType, BaseDatabaseProvider> pluginDatabases = activeDatabases.get(pluginName);
        if (pluginDatabases != null) {
            BaseDatabaseProvider provider = pluginDatabases.remove(databaseType);
            if (provider != null) {
                provider.disconnect();
                DataProvider.getInstance().getLogger().info(pluginName + " unregistered database: " + databaseType.name());
            }
            if (pluginDatabases.isEmpty()) {
                activeDatabases.remove(pluginName);
            }
        }
    }

    /**
     * Unregisters (and disconnects) all databases for a given plugin.
     *
     * @param pluginName the name of the plugin.
     */
    public void unregisterAllDatabases(String pluginName) {
        if (activeDatabases.containsKey(pluginName)) {
            for (DatabaseType type : activeDatabases.get(pluginName).keySet()) {
                unregisterDatabase(pluginName, type);
            }
        }
    }

    /**
     * Shuts down all active database connections.
     */
    public void shutdownAllDatabases() {
        for (Map.Entry<String, ConcurrentMap<DatabaseType, BaseDatabaseProvider>> entry : activeDatabases.entrySet()) {
            String pluginName = entry.getKey();
            for (Map.Entry<DatabaseType, BaseDatabaseProvider> dbEntry : entry.getValue().entrySet()) {
                try {
                    dbEntry.getValue().disconnect();
                } catch (Exception e) {
                    DataProvider.getInstance().getLogger().log(Level.SEVERE,
                            "Error disconnecting database " + dbEntry.getKey() + " for plugin " + pluginName, e);
                }
            }
        }
        activeDatabases.clear();
        DataProvider.getInstance().getLogger().info("All database connections have been closed.");
    }

    public ConcurrentMap<String, ConcurrentMap<DatabaseType, BaseDatabaseProvider>> getActiveDatabases() {
        return activeDatabases;
    }
}
