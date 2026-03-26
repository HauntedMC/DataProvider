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
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

class DataProviderRegistry {

    private static final String SHUTDOWN_MESSAGE =
            "DataProvider is shut down. Obtain a fresh API instance after plugin enable.";

    private final ConcurrentMap<DatabaseConnectionKey, ActiveDatabaseRegistration> activeDatabases = new ConcurrentHashMap<>();
    private final ReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
    private final DatabaseFactory factory;
    private final ConfigHandler configHandler;
    private final ILoggerAdapter logger;
    private volatile boolean closed;

    public DataProviderRegistry(DatabaseFactory factory, ConfigHandler configHandler, ILoggerAdapter logger) {
        this.factory = Objects.requireNonNull(factory, "Factory cannot be null.");
        this.configHandler = Objects.requireNonNull(configHandler, "Config handler cannot be null.");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    protected DatabaseProvider registerDatabase(
            String pluginName,
            String ownerScope,
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        Objects.requireNonNull(pluginName, "Plugin name cannot be null.");
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        Objects.requireNonNull(databaseType, "Database type cannot be null.");
        Objects.requireNonNull(connectionIdentifier, "Connection identifier cannot be null.");
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            DatabaseConnectionKey key = new DatabaseConnectionKey(pluginName, databaseType, connectionIdentifier);

            while (true) {
                ActiveDatabaseRegistration existingRegistration = activeDatabases.get(key);
                if (existingRegistration != null) {
                    ManagedDatabaseProvider existingProvider = existingRegistration.provider();
                    if (isProviderHealthy(existingProvider, key) && existingRegistration.tryAcquireReference(ownerScope)) {
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
                    logger.error("Failed to establish connection for " + pluginName + " with " + databaseType.name()
                            + ": This database type is disabled in the main config.");
                    return null;
                }

                ManagedDatabaseProvider createdProvider = null;
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
                        logger.error("Failed to establish connection for " + pluginName + " with " + databaseType.name()
                                + " (" + connectionIdentifier + ")");
                        return null;
                    }

                    ActiveDatabaseRegistration createdRegistration = new ActiveDatabaseRegistration(
                            createdProvider,
                            ownerScope
                    );
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

                    ManagedDatabaseProvider raceWinnerProvider = raceWinner.provider();
                    if (isProviderHealthy(raceWinnerProvider, key) && raceWinner.tryAcquireReference(ownerScope)) {
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
        } finally {
            readLock.unlock();
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

    private void disconnectQuietly(ManagedDatabaseProvider provider, DatabaseConnectionKey key, String reason) {
        try {
            provider.disconnect();
        } catch (Exception e) {
            logger.error("Failed to clean up " + reason + " for " + key, e);
        }
    }

    protected DatabaseProvider getDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        Objects.requireNonNull(pluginName, "Plugin name cannot be null.");
        Objects.requireNonNull(databaseType, "Database type cannot be null.");
        Objects.requireNonNull(connectionIdentifier, "Connection identifier cannot be null.");
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            DatabaseConnectionKey key = new DatabaseConnectionKey(pluginName, databaseType, connectionIdentifier);
            ActiveDatabaseRegistration registration = activeDatabases.get(key);
            if (registration == null) {
                return null;
            }

            ManagedDatabaseProvider provider = registration.provider();
            if (isProviderHealthy(provider, key)) {
                return provider;
            }

            if (activeDatabases.remove(key, registration)) {
                disconnectQuietly(provider, key, "stale connection during lookup");
                logger.warn("Removed stale " + databaseType.name() + " connection for " + pluginName
                        + " (" + connectionIdentifier + ") while retrieving the provider.");
            }
            return null;
        } finally {
            readLock.unlock();
        }
    }

    protected void unregisterDatabase(
            String pluginName,
            String ownerScope,
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        Objects.requireNonNull(pluginName, "Plugin name cannot be null.");
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        Objects.requireNonNull(databaseType, "Database type cannot be null.");
        Objects.requireNonNull(connectionIdentifier, "Connection identifier cannot be null.");
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            DatabaseConnectionKey key = new DatabaseConnectionKey(pluginName, databaseType, connectionIdentifier);
            ActiveDatabaseRegistration registration = activeDatabases.get(key);
            if (registration == null) {
                return;
            }

            ReferenceReleaseResult releaseResult = registration.releaseReference(ownerScope);
            if (!releaseResult.ownerHadReference()) {
                logger.warn(pluginName + " attempted to release " + databaseType.name() + " connection ("
                        + connectionIdentifier + ") from unregistered scope " + ownerScope);
                return;
            }
            int references = releaseResult.totalReferences();
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
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Releases registrations for a specific plugin + owner scope pair.
     */
    protected void unregisterAllDatabases(String pluginName, String ownerScope) {
        Objects.requireNonNull(pluginName, "Plugin name cannot be null.");
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        Lock writeLock = lifecycleLock.writeLock();
        writeLock.lock();
        try {
            ensureOpen();
            for (Map.Entry<DatabaseConnectionKey, ActiveDatabaseRegistration> entry : activeDatabases.entrySet()) {
                DatabaseConnectionKey key = entry.getKey();
                if (!key.pluginName().equals(pluginName)) {
                    continue;
                }

                ActiveDatabaseRegistration registration = entry.getValue();
                int referencesAfterRelease = registration.releaseAllForOwner(ownerScope);
                if (referencesAfterRelease > 0) {
                    continue;
                }

                if (!activeDatabases.remove(key, registration)) {
                    continue;
                }
                try {
                    registration.provider().disconnect();
                } catch (Exception e) {
                    logger.error("Error disconnecting " + key, e);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Force-releases every registration for the plugin, regardless of owner scope.
     * Intended for deterministic plugin/process shutdown cleanup.
     */
    protected void unregisterAllDatabasesForPlugin(String pluginName) {
        Objects.requireNonNull(pluginName, "Plugin name cannot be null.");
        Lock writeLock = lifecycleLock.writeLock();
        writeLock.lock();
        try {
            ensureOpen();
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
        } finally {
            writeLock.unlock();
        }
    }

    protected void shutdownAllDatabases() {
        Lock writeLock = lifecycleLock.writeLock();
        writeLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            for (Map.Entry<DatabaseConnectionKey, ActiveDatabaseRegistration> entry : activeDatabases.entrySet()) {
                try {
                    entry.getValue().provider().disconnect();
                } catch (Exception e) {
                    logger.error("Error disconnecting " + entry.getKey(), e);
                }
            }
            activeDatabases.clear();
            logger.info("All database connections have been closed.");
        } finally {
            writeLock.unlock();
        }
    }

    protected ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> getActiveDatabases() {
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> snapshot = new ConcurrentHashMap<>();
            for (Map.Entry<DatabaseConnectionKey, ActiveDatabaseRegistration> entry : activeDatabases.entrySet()) {
                snapshot.put(entry.getKey(), entry.getValue().provider());
            }
            return snapshot;
        } finally {
            readLock.unlock();
        }
    }

    protected Map<DatabaseConnectionKey, Integer> getActiveDatabaseReferenceCounts() {
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            Map<DatabaseConnectionKey, Integer> snapshot = new HashMap<>();
            for (Map.Entry<DatabaseConnectionKey, ActiveDatabaseRegistration> entry : activeDatabases.entrySet()) {
                snapshot.put(entry.getKey(), entry.getValue().referenceCount());
            }
            return snapshot;
        } finally {
            readLock.unlock();
        }
    }

    protected boolean isClosed() {
        return closed;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(SHUTDOWN_MESSAGE);
        }
    }

    private static final class ActiveDatabaseRegistration {
        private final ManagedDatabaseProvider provider;
        // Tracks ownership per logical scope (default plugin scope or explicit scope string).
        private final Map<String, Integer> ownerReferenceCounts = new HashMap<>();
        // Total references across all owner scopes for this (plugin, type, identifier) key.
        private int referenceCount;

        private ActiveDatabaseRegistration(ManagedDatabaseProvider provider, String initialOwnerScope) {
            this.provider = Objects.requireNonNull(provider, "Database provider cannot be null.");
            if (initialOwnerScope == null || initialOwnerScope.isBlank()) {
                throw new IllegalArgumentException("Initial owner scope cannot be null or blank.");
            }
            this.referenceCount = 1;
            ownerReferenceCounts.put(initialOwnerScope, 1);
        }

        private ManagedDatabaseProvider provider() {
            return provider;
        }

        private synchronized boolean tryAcquireReference(String ownerScope) {
            if (ownerScope == null || ownerScope.isBlank()) {
                return false;
            }
            if (referenceCount <= 0) {
                return false;
            }
            referenceCount++;
            ownerReferenceCounts.merge(ownerScope, 1, Integer::sum);
            return true;
        }

        private synchronized ReferenceReleaseResult releaseReference(String ownerScope) {
            if (ownerScope == null || ownerScope.isBlank() || referenceCount <= 0) {
                return new ReferenceReleaseResult(false, Math.max(referenceCount, 0));
            }
            Integer ownerCount = ownerReferenceCounts.get(ownerScope);
            if (ownerCount == null || ownerCount <= 0) {
                return new ReferenceReleaseResult(false, Math.max(referenceCount, 0));
            }

            if (ownerCount == 1) {
                ownerReferenceCounts.remove(ownerScope);
            } else {
                ownerReferenceCounts.put(ownerScope, ownerCount - 1);
            }

            referenceCount = Math.max(0, referenceCount - 1);
            return new ReferenceReleaseResult(true, referenceCount);
        }

        private synchronized int releaseAllForOwner(String ownerScope) {
            if (ownerScope == null || ownerScope.isBlank() || referenceCount <= 0) {
                return Math.max(referenceCount, 0);
            }
            Integer ownerCount = ownerReferenceCounts.remove(ownerScope);
            if (ownerCount == null || ownerCount <= 0) {
                return Math.max(referenceCount, 0);
            }
            referenceCount = Math.max(0, referenceCount - ownerCount);
            return referenceCount;
        }

        private synchronized int referenceCount() {
            return Math.max(referenceCount, 0);
        }

        private synchronized void forceReleaseAll() {
            referenceCount = 0;
            ownerReferenceCounts.clear();
        }
    }

    private record ReferenceReleaseResult(boolean ownerHadReference, int totalReferences) {
    }
}
