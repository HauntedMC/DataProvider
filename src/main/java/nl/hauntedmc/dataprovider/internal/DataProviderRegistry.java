package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.config.ConfigHandler;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

class DataProviderRegistry {

    private final ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> activeDatabases = new ConcurrentHashMap<>();
    private final DatabaseFactory factory;
    private final ConfigHandler configHandler;
    private final ILoggerAdapter logger;

    public DataProviderRegistry(DatabaseFactory factory, ConfigHandler configHandler, ILoggerAdapter logger) {
        this.factory = factory;
        this.configHandler = Objects.requireNonNull(configHandler, "Config handler cannot be null.");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    protected DatabaseProvider registerDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        DatabaseConnectionKey key = new DatabaseConnectionKey(pluginName, databaseType, connectionIdentifier);

        DatabaseProvider existingProvider = activeDatabases.get(key);
        if (existingProvider != null) {
            logger.info(pluginName + " already has a " + databaseType.name() + " connection with identifier: " + connectionIdentifier);
            return existingProvider;
        }

        if (!configHandler.isDatabaseTypeEnabled(databaseType)) {
            logger.error("Failed to establish connection for " + pluginName + " with " + databaseType.name() + ": This database type is disabled in the main config.");
            return null;
        }

        DatabaseProvider databaseProvider = null;
        try {
            databaseProvider = factory.createDatabaseProvider(databaseType, connectionIdentifier);
            if (databaseProvider == null) {
                return null;
            }
            databaseProvider.connect();
            if (!databaseProvider.isConnected()) {
                try {
                    databaseProvider.disconnect();
                } catch (Exception e) {
                    logger.error("Failed to clean up failed connection for " + key, e);
                }
                logger.error("Failed to establish connection for " + pluginName + " with " + databaseType.name() + " (" + connectionIdentifier + ")");
                return null;
            }
            DatabaseProvider raceWinner = activeDatabases.putIfAbsent(key, databaseProvider);
            if (raceWinner != null) {
                try {
                    databaseProvider.disconnect();
                } catch (Exception e) {
                    logger.error("Failed to clean up duplicate connection for " + key, e);
                }
                logger.info(pluginName + " already has a " + databaseType.name() + " connection with identifier: " + connectionIdentifier);
                return raceWinner;
            }
            logger.info(pluginName + " registered " + databaseType.name() + " connection (" + connectionIdentifier + ")");
            return databaseProvider;
        } catch (Exception e) {
            if (databaseProvider != null) {
                try {
                    databaseProvider.disconnect();
                } catch (Exception disconnectException) {
                    logger.error("Failed to clean up errored connection for " + key, disconnectException);
                }
            }
            logger.error("Failed to register database for " + pluginName, e);
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
            try {
                provider.disconnect();
            } catch (Exception e) {
                logger.error("Error disconnecting " + key, e);
            }
            logger.info(pluginName + " unregistered " + databaseType.name() + " connection (" + connectionIdentifier + ")");
        }
    }

    protected void unregisterAllDatabases(String pluginName) {
        activeDatabases.entrySet().removeIf(entry -> {
            if (entry.getKey().pluginName().equals(pluginName)) {
                try {
                    entry.getValue().disconnect();
                } catch (Exception e) {
                    logger.error("Error disconnecting " + entry.getKey(), e);
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
                logger.error("Error disconnecting " + entry.getKey(), e);
            }
        }
        activeDatabases.clear();
        logger.info("All database connections have been closed.");
    }

    protected ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> getActiveDatabases() {
        return new ConcurrentHashMap<>(activeDatabases);
    }
}
