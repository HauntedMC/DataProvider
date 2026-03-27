package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.config.ConfigHandler;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;

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

    /**
     * Active registrations keyed by typed plugin/type/identifier identity.
     */
    private final ConcurrentMap<RegistrationKey, ActiveDatabaseRegistration> activeDatabases = new ConcurrentHashMap<>();
    /**
     * Guards lifecycle transitions (shutdown / bulk unregister) while allowing concurrent
     * register/get/unregister operations through the read lock.
     */
    private final ReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
    private final DatabaseFactory factory;
    private final ConfigHandler configHandler;
    private final LoggerAdapter logger;
    private volatile boolean closed;

    public DataProviderRegistry(DatabaseFactory factory, ConfigHandler configHandler, LoggerAdapter logger) {
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
        return registerDatabase(
                PluginId.of(pluginName),
                OwnerScopeId.of(ownerScope),
                databaseType,
                ConnectionIdentifier.of(connectionIdentifier)
        );
    }

    DatabaseProvider registerDatabase(
            PluginId pluginId,
            OwnerScopeId ownerScope,
            DatabaseType databaseType,
            ConnectionIdentifier connectionIdentifier
    ) {
        Objects.requireNonNull(pluginId, "Plugin id cannot be null.");
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        Objects.requireNonNull(databaseType, "Database type cannot be null.");
        Objects.requireNonNull(connectionIdentifier, "Connection identifier cannot be null.");
        RegistrationKey key = new RegistrationKey(pluginId, databaseType, connectionIdentifier);
        String pluginName = key.pluginId().value();
        String identifierValue = key.connectionIdentifier().value();
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();

            while (true) {
                ActiveDatabaseRegistration existingRegistration = activeDatabases.get(key);
                if (existingRegistration != null) {
                    ManagedDatabaseProvider existingProvider = existingRegistration.provider();
                    if (isProviderHealthy(existingProvider, key) && existingRegistration.tryAcquireReference(ownerScope)) {
                        int references = existingRegistration.referenceCount();
                        logger.info(pluginName + " reused " + databaseType.name() + " connection (" + identifierValue
                                + "), active references=" + references);
                        return existingProvider;
                    }
                    if (!activeDatabases.remove(key, existingRegistration)) {
                        continue;
                    }
                    disconnectQuietly(existingProvider, key, "stale existing connection");
                    logger.warn("Removed stale " + databaseType.name() + " connection for " + pluginName
                            + " (" + identifierValue + ") before re-registering.");
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
                                + " (" + identifierValue + ")");
                        return null;
                    }

                    ActiveDatabaseRegistration createdRegistration = new ActiveDatabaseRegistration(
                            createdProvider,
                            ownerScope
                    );
                    ActiveDatabaseRegistration raceWinner = activeDatabases.putIfAbsent(key, createdRegistration);
                    if (raceWinner == null) {
                        logger.info(pluginName + " registered " + databaseType.name() + " connection (" + identifierValue
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
                        logger.info(pluginName + " already has " + databaseType.name() + " connection (" + identifierValue
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

    private boolean isProviderHealthy(DatabaseProvider provider, RegistrationKey key) {
        try {
            return provider.isConnected();
        } catch (Exception e) {
            logger.warn("Provider health check failed for " + key + ". Treating connection as stale.");
            return false;
        }
    }

    private void disconnectQuietly(ManagedDatabaseProvider provider, RegistrationKey key, String reason) {
        try {
            provider.disconnect();
        } catch (Exception e) {
            logger.error("Failed to clean up " + reason + " for " + key, e);
        }
    }

    protected DatabaseProvider getDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        return getDatabase(
                PluginId.of(pluginName),
                databaseType,
                ConnectionIdentifier.of(connectionIdentifier)
        );
    }

    DatabaseProvider getDatabase(
            PluginId pluginId,
            DatabaseType databaseType,
            ConnectionIdentifier connectionIdentifier
    ) {
        Objects.requireNonNull(pluginId, "Plugin id cannot be null.");
        Objects.requireNonNull(databaseType, "Database type cannot be null.");
        Objects.requireNonNull(connectionIdentifier, "Connection identifier cannot be null.");
        RegistrationKey key = new RegistrationKey(pluginId, databaseType, connectionIdentifier);
        String pluginName = key.pluginId().value();
        String identifierValue = key.connectionIdentifier().value();
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
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
                        + " (" + identifierValue + ") while retrieving the provider.");
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
        unregisterDatabase(
                PluginId.of(pluginName),
                OwnerScopeId.of(ownerScope),
                databaseType,
                ConnectionIdentifier.of(connectionIdentifier)
        );
    }

    void unregisterDatabase(
            PluginId pluginId,
            OwnerScopeId ownerScope,
            DatabaseType databaseType,
            ConnectionIdentifier connectionIdentifier
    ) {
        Objects.requireNonNull(pluginId, "Plugin id cannot be null.");
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        Objects.requireNonNull(databaseType, "Database type cannot be null.");
        Objects.requireNonNull(connectionIdentifier, "Connection identifier cannot be null.");
        RegistrationKey key = new RegistrationKey(pluginId, databaseType, connectionIdentifier);
        String pluginName = key.pluginId().value();
        String identifierValue = key.connectionIdentifier().value();
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            ActiveDatabaseRegistration registration = activeDatabases.get(key);
            if (registration == null) {
                return;
            }

            ReferenceReleaseResult releaseResult = registration.releaseReference(ownerScope);
            if (!releaseResult.ownerHadReference()) {
                logger.warn(pluginName + " attempted to release " + databaseType.name() + " connection ("
                        + identifierValue + ") from unregistered scope " + ownerScope.value());
                return;
            }
            int references = releaseResult.totalReferences();
            if (references > 0) {
                logger.info(pluginName + " released " + databaseType.name() + " connection (" + identifierValue
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
            logger.info(pluginName + " unregistered " + databaseType.name() + " connection (" + identifierValue + ")");
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Releases registrations for a specific plugin + owner scope pair.
     */
    protected void unregisterAllDatabases(String pluginName, String ownerScope) {
        unregisterAllDatabases(PluginId.of(pluginName), OwnerScopeId.of(ownerScope));
    }

    void unregisterAllDatabases(PluginId pluginId, OwnerScopeId ownerScope) {
        Objects.requireNonNull(pluginId, "Plugin id cannot be null.");
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        Lock writeLock = lifecycleLock.writeLock();
        writeLock.lock();
        try {
            ensureOpen();
            for (Map.Entry<RegistrationKey, ActiveDatabaseRegistration> entry : activeDatabases.entrySet()) {
                RegistrationKey key = entry.getKey();
                if (!key.pluginId().equals(pluginId)) {
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
        unregisterAllDatabasesForPlugin(PluginId.of(pluginName));
    }

    void unregisterAllDatabasesForPlugin(PluginId pluginId) {
        Objects.requireNonNull(pluginId, "Plugin id cannot be null.");
        Lock writeLock = lifecycleLock.writeLock();
        writeLock.lock();
        try {
            ensureOpen();
            for (Map.Entry<RegistrationKey, ActiveDatabaseRegistration> entry : activeDatabases.entrySet()) {
                RegistrationKey key = entry.getKey();
                if (!key.pluginId().equals(pluginId)) {
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
            for (Map.Entry<RegistrationKey, ActiveDatabaseRegistration> entry : activeDatabases.entrySet()) {
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
            for (Map.Entry<RegistrationKey, ActiveDatabaseRegistration> entry : activeDatabases.entrySet()) {
                snapshot.put(entry.getKey().toExternalKey(), entry.getValue().provider());
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
            for (Map.Entry<RegistrationKey, ActiveDatabaseRegistration> entry : activeDatabases.entrySet()) {
                snapshot.put(entry.getKey().toExternalKey(), entry.getValue().referenceCount());
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

    /**
     * Internal key representation that keeps identity typed across registry operations.
     * Conversion to {@link DatabaseConnectionKey} is done only for external snapshots.
     */
    private record RegistrationKey(PluginId pluginId, DatabaseType type, ConnectionIdentifier connectionIdentifier) {
        private RegistrationKey {
            Objects.requireNonNull(pluginId, "Plugin id cannot be null.");
            Objects.requireNonNull(type, "Database type cannot be null.");
            Objects.requireNonNull(connectionIdentifier, "Connection identifier cannot be null.");
        }

        private DatabaseConnectionKey toExternalKey() {
            return new DatabaseConnectionKey(pluginId.value(), type, connectionIdentifier.value());
        }
    }

    private static final class ActiveDatabaseRegistration {
        private final ManagedDatabaseProvider provider;
        // Tracks ownership per logical scope (default plugin scope or explicit scope string).
        private final Map<OwnerScopeId, Integer> ownerReferenceCounts = new HashMap<>();
        // Total references across all owner scopes for this (plugin, type, identifier) key.
        private int referenceCount;

        private ActiveDatabaseRegistration(ManagedDatabaseProvider provider, OwnerScopeId initialOwnerScope) {
            this.provider = Objects.requireNonNull(provider, "Database provider cannot be null.");
            Objects.requireNonNull(initialOwnerScope, "Initial owner scope cannot be null.");
            this.referenceCount = 1;
            ownerReferenceCounts.put(initialOwnerScope, 1);
        }

        private ManagedDatabaseProvider provider() {
            return provider;
        }

        private synchronized boolean tryAcquireReference(OwnerScopeId ownerScope) {
            if (ownerScope == null) {
                return false;
            }
            if (referenceCount <= 0) {
                return false;
            }
            referenceCount++;
            ownerReferenceCounts.merge(ownerScope, 1, Integer::sum);
            return true;
        }

        private synchronized ReferenceReleaseResult releaseReference(OwnerScopeId ownerScope) {
            if (ownerScope == null || referenceCount <= 0) {
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

        private synchronized int releaseAllForOwner(OwnerScopeId ownerScope) {
            if (ownerScope == null || referenceCount <= 0) {
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
