package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

class DataProviderRegistry {

    private final ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> activeDatabases = new ConcurrentHashMap<>();
    private final DatabaseFactory factory;

    public DataProviderRegistry(DatabaseFactory factory) {
        this.factory = factory;
    }

    protected DatabaseProvider registerDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        DatabaseConnectionKey key = new DatabaseConnectionKey(pluginName, databaseType, connectionIdentifier);

        DatabaseProvider existingProvider = activeDatabases.get(key);
        if (existingProvider != null) {
            DataProvider.getLogger().info(pluginName + " already has a " + databaseType.name() + " connection with identifier: " + connectionIdentifier);
            return existingProvider;
        }

        if (!DataProvider.getConfigHandler().isDatabaseTypeEnabled(databaseType)) {
            DataProvider.getLogger().error("Failed to establish connection for " + pluginName + " with " + databaseType.name() + ": This database type is disabled in the main config.");
            return null;
        }

        try {
            DatabaseProvider databaseProvider = factory.createDatabaseProvider(databaseType, connectionIdentifier);
            if (databaseProvider == null) {
                return null;
            }
            databaseProvider.connect();
            if (!databaseProvider.isConnected()) {
                DataProvider.getLogger().error("Failed to establish connection for " + pluginName + " with " + databaseType.name() + " (" + connectionIdentifier + ")");
                return null;
            }
            DatabaseProvider raceWinner = activeDatabases.putIfAbsent(key, databaseProvider);
            if (raceWinner != null) {
                try {
                    databaseProvider.disconnect();
                } catch (Exception e) {
                    DataProvider.getLogger().error("Failed to clean up duplicate connection for " + key, e);
                }
                DataProvider.getLogger().info(pluginName + " already has a " + databaseType.name() + " connection with identifier: " + connectionIdentifier);
                return raceWinner;
            }
            DataProvider.getLogger().info(pluginName + " registered " + databaseType.name() + " connection (" + connectionIdentifier + ")");
            return databaseProvider;
        } catch (Exception e) {
            DataProvider.getLogger().error("Failed to register database for " + pluginName, e);
            return null;
        }
    }

    protected DatabaseProvider getDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        DatabaseConnectionKey key = new DatabaseConnectionKey(pluginName, databaseType, connectionIdentifier);
        return activeDatabases.get(key);
    }

    protected void unregisterDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        DatabaseConnectionKey key = new DatabaseConnectionKey(pluginName, databaseType, connectionIdentifier);
        DatabaseProvider provider = activeDatabases.remove(key);
        if (provider != null) {
            provider.disconnect();
            DataProvider.getLogger().info(pluginName + " unregistered " + databaseType.name() + " connection (" + connectionIdentifier + ")");
        }
    }

    protected void unregisterAllDatabases(String pluginName) {
        activeDatabases.entrySet().removeIf(entry -> {
            if (entry.getKey().pluginName().equals(pluginName)) {
                try {
                    entry.getValue().disconnect();
                } catch (Exception e) {
                    DataProvider.getLogger().error("Error disconnecting " + entry.getKey(), e);
                }
                return true;
            }
            return false;
        });
    }

    protected void shutdownAllDatabases() {
        for (Map.Entry<DatabaseConnectionKey, DatabaseProvider> entry : activeDatabases.entrySet()) {
            try {
                entry.getValue().disconnect();
            } catch (Exception e) {
                DataProvider.getLogger().error("Error disconnecting " + entry.getKey(), e);
            }
        }
        activeDatabases.clear();
        DataProvider.getLogger().info("All database connections have been closed.");
    }

    protected ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> getActiveDatabases() {
        return new ConcurrentHashMap<>(activeDatabases);
    }
}
