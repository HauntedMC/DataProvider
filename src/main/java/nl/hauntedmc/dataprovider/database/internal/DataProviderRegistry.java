package nl.hauntedmc.dataprovider.database.internal;

import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;
import nl.hauntedmc.dataprovider.logger.DPLogger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

class DataProviderRegistry {

    private final ConcurrentMap<DatabaseConnectionKey, BaseDatabaseProvider> activeDatabases = new ConcurrentHashMap<>();

    protected BaseDatabaseProvider registerDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        DatabaseConnectionKey key = new DatabaseConnectionKey(pluginName, databaseType, connectionIdentifier);

        if (activeDatabases.containsKey(key)) {
            DPLogger.info(pluginName + " already has a " + databaseType.name() + " connection with identifier: " + connectionIdentifier);
            return activeDatabases.get(key);
        }

        try {
            BaseDatabaseProvider databaseProvider = DatabaseFactory.createDatabaseProvider(databaseType, connectionIdentifier);
            if (databaseProvider == null) {
                return null;
            }
            databaseProvider.connect();
            if (!databaseProvider.isConnected()) {
                DPLogger.error("Failed to establish connection for " + pluginName + " with " + databaseType.name() + " (" + connectionIdentifier + ")");
                return null;
            }
            activeDatabases.put(key, databaseProvider);
            DPLogger.info(pluginName + " registered " + databaseType.name() + " connection (" + connectionIdentifier + ")");
            return databaseProvider;
        } catch (Exception e) {
            DPLogger.error("Failed to register database for " + pluginName, e);
            return null;
        }
    }

    protected BaseDatabaseProvider getDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        DatabaseConnectionKey key = new DatabaseConnectionKey(pluginName, databaseType, connectionIdentifier);
        return activeDatabases.get(key);
    }

    protected void unregisterDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        DatabaseConnectionKey key = new DatabaseConnectionKey(pluginName, databaseType, connectionIdentifier);
        BaseDatabaseProvider provider = activeDatabases.remove(key);
        if (provider != null) {
            provider.disconnect();
            DPLogger.info(pluginName + " unregistered " + databaseType.name() + " connection (" + connectionIdentifier + ")");
        }
    }

    protected void unregisterAllDatabases(String pluginName) {
        activeDatabases.entrySet().removeIf(entry -> {
            if (entry.getKey().pluginName().equals(pluginName)) {
                try {
                    entry.getValue().disconnect();
                } catch (Exception e) {
                    DPLogger.error("Error disconnecting " + entry.getKey(), e);
                }
                return true;
            }
            return false;
        });
    }

    protected void shutdownAllDatabases() {
        for (Map.Entry<DatabaseConnectionKey, BaseDatabaseProvider> entry : activeDatabases.entrySet()) {
            try {
                entry.getValue().disconnect();
            } catch (Exception e) {
                DPLogger.error("Error disconnecting " + entry.getKey(), e);
            }
        }
        activeDatabases.clear();
        DPLogger.info("All database connections have been closed.");
    }

    protected ConcurrentMap<DatabaseConnectionKey, BaseDatabaseProvider> getActiveDatabases() {
        return activeDatabases;
    }
}
