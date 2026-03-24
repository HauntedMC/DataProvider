package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.config.ConfigHandler;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

class DataProviderRegistry {

    private final ConcurrentMap<DatabaseConnectionKey, ActiveDatabaseRegistration> activeDatabases = new ConcurrentHashMap<>();
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

        while (true) {
            ActiveDatabaseRegistration existingRegistration = activeDatabases.get(key);
            if (existingRegistration != null) {
                DatabaseProvider existingProvider = existingRegistration.provider();
                if (isProviderHealthy(existingProvider, key) && existingRegistration.tryAcquireReference()) {
                    int references = existingRegistration.referenceCount();
                    logger.info(pluginName + " reused " + databaseType.name() + " connection (" + connectionIdentifier
                            + "), active references=" + references);
                    return existingProvider;
                }
                if (!activeDatabases.remove(key, existingRegistration)) {
                    continue;
                }
                disconnectQuietly(existingProvider, key, "stale existing connection");
                logger.warn("Removed stale " + databaseType.name() + " connection for " + pluginName
                        + " (" + connectionIdentifier + ") before re-registering.");
            }

            if (!configHandler.isDatabaseTypeEnabled(databaseType)) {
                logger.error("Failed to establish connection for " + pluginName + " with " + databaseType.name() + ": This database type is disabled in the main config.");
                return null;
            }

            DatabaseProvider createdProvider = null;
            try {
                createdProvider = factory.createDatabaseProvider(databaseType, connectionIdentifier);
                if (createdProvider == null) {
                    return null;
                }
                createdProvider.connect();
                if (!createdProvider.isConnected()) {
                    try {
                        createdProvider.disconnect();
                    } catch (Exception e) {
                        logger.error("Failed to clean up failed connection for " + key, e);
                    }
                    logger.error("Failed to establish connection for " + pluginName + " with " + databaseType.name() + " (" + connectionIdentifier + ")");
                    return null;
                }

                ActiveDatabaseRegistration createdRegistration = new ActiveDatabaseRegistration(createdProvider);
                ActiveDatabaseRegistration raceWinner = activeDatabases.putIfAbsent(key, createdRegistration);
                if (raceWinner == null) {
                    logger.info(pluginName + " registered " + databaseType.name() + " connection (" + connectionIdentifier
                            + "), active references=1");
                    return createdProvider;
                }

                try {
                    createdProvider.disconnect();
                } catch (Exception e) {
                    logger.error("Failed to clean up duplicate connection for " + key, e);
                }

                DatabaseProvider raceWinnerProvider = raceWinner.provider();
                if (isProviderHealthy(raceWinnerProvider, key) && raceWinner.tryAcquireReference()) {
                    int references = raceWinner.referenceCount();
                    logger.info(pluginName + " already has " + databaseType.name() + " connection (" + connectionIdentifier
                            + "), active references=" + references);
                    return raceWinnerProvider;
                }

                if (activeDatabases.remove(key, raceWinner)) {
                    disconnectQuietly(raceWinnerProvider, key, "stale raced connection");
                }
            } catch (Exception e) {
                if (createdProvider != null) {
                    try {
                        createdProvider.disconnect();
                    } catch (Exception disconnectException) {
                        logger.error("Failed to clean up errored connection for " + key, disconnectException);
                    }
                }
                logger.error("Failed to register database for " + pluginName, e);
                return null;
            }
        }
    }

    private boolean isProviderHealthy(DatabaseProvider provider, DatabaseConnectionKey key) {
        try {
            return provider.isConnected();
        } catch (Exception e) {
            logger.warn("Provider health check failed for " + key + ". Treating connection as stale.");
            return false;
        }
    }

    private void disconnectQuietly(DatabaseProvider provider, DatabaseConnectionKey key, String reason) {
        try {
            provider.disconnect();
        } catch (Exception e) {
            logger.error("Failed to clean up " + reason + " for " + key, e);
        }
    }

    protected DatabaseProvider getDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        DatabaseConnectionKey key = new DatabaseConnectionKey(pluginName, databaseType, connectionIdentifier);
        ActiveDatabaseRegistration registration = activeDatabases.get(key);
        if (registration == null) {
            return null;
        }

        DatabaseProvider provider = registration.provider();
        if (isProviderHealthy(provider, key)) {
            return provider;
        }

        if (activeDatabases.remove(key, registration)) {
            disconnectQuietly(provider, key, "stale connection during lookup");
            logger.warn("Removed stale " + databaseType.name() + " connection for " + pluginName
                    + " (" + connectionIdentifier + ") while retrieving the provider.");
        }
        return null;
    }

    protected void unregisterDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        DatabaseConnectionKey key = new DatabaseConnectionKey(pluginName, databaseType, connectionIdentifier);
        ActiveDatabaseRegistration registration = activeDatabases.get(key);
        if (registration == null) {
            return;
        }

        int references = registration.releaseReference();
        if (references > 0) {
            logger.info(pluginName + " released " + databaseType.name() + " connection (" + connectionIdentifier
                    + "), remaining references=" + references);
            return;
        }

        if (!activeDatabases.remove(key, registration)) {
            return;
        }

        try {
            registration.provider().disconnect();
        } catch (Exception e) {
            logger.error("Error disconnecting " + key, e);
        }
        logger.info(pluginName + " unregistered " + databaseType.name() + " connection (" + connectionIdentifier + ")");
    }

    protected void unregisterAllDatabases(String pluginName) {
        for (Map.Entry<DatabaseConnectionKey, ActiveDatabaseRegistration> entry : activeDatabases.entrySet()) {
            DatabaseConnectionKey key = entry.getKey();
            if (!key.pluginName().equals(pluginName)) {
                continue;
            }

            ActiveDatabaseRegistration registration = entry.getValue();
            if (!activeDatabases.remove(key, registration)) {
                continue;
            }

            registration.forceReleaseAll();
            try {
                registration.provider().disconnect();
            } catch (Exception e) {
                logger.error("Error disconnecting " + key, e);
            }
        }
    }

    protected void shutdownAllDatabases() {
        for (Map.Entry<DatabaseConnectionKey, ActiveDatabaseRegistration> entry : activeDatabases.entrySet()) {
            try {
                entry.getValue().provider().disconnect();
            } catch (Exception e) {
                logger.error("Error disconnecting " + entry.getKey(), e);
            }
        }
        activeDatabases.clear();
        logger.info("All database connections have been closed.");
    }

    protected ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> getActiveDatabases() {
        ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> snapshot = new ConcurrentHashMap<>();
        for (Map.Entry<DatabaseConnectionKey, ActiveDatabaseRegistration> entry : activeDatabases.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().provider());
        }
        return snapshot;
    }

    protected Map<DatabaseConnectionKey, Integer> getActiveDatabaseReferenceCounts() {
        Map<DatabaseConnectionKey, Integer> snapshot = new HashMap<>();
        for (Map.Entry<DatabaseConnectionKey, ActiveDatabaseRegistration> entry : activeDatabases.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().referenceCount());
        }
        return snapshot;
    }

    private static final class ActiveDatabaseRegistration {
        private final DatabaseProvider provider;
        private final AtomicInteger referenceCount;

        private ActiveDatabaseRegistration(DatabaseProvider provider) {
            this.provider = Objects.requireNonNull(provider, "Database provider cannot be null.");
            this.referenceCount = new AtomicInteger(1);
        }

        private DatabaseProvider provider() {
            return provider;
        }

        private boolean tryAcquireReference() {
            while (true) {
                int current = referenceCount.get();
                if (current <= 0) {
                    return false;
                }
                if (referenceCount.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }

        private int releaseReference() {
            while (true) {
                int current = referenceCount.get();
                if (current <= 0) {
                    return 0;
                }
                int next = current - 1;
                if (referenceCount.compareAndSet(current, next)) {
                    return next;
                }
            }
        }

        private int referenceCount() {
            return Math.max(referenceCount.get(), 0);
        }

        private void forceReleaseAll() {
            referenceCount.set(0);
        }
    }
}
