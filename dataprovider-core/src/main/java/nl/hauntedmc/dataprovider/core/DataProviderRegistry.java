package nl.hauntedmc.dataprovider.core;

import nl.hauntedmc.dataprovider.core.config.ConfigHandler;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

class DataProviderRegistry {

    private static final String SHUTDOWN_MESSAGE =
            "DataProvider is shut down. Obtain a fresh API instance after plugin enable.";

    private final ConcurrentMap<RegistrationKey, ProviderSlot> activeDatabases = new ConcurrentHashMap<>();
    private final ConcurrentMap<RegistrationKey, ConnectionHealthSnapshot> healthSnapshots = new ConcurrentHashMap<>();
    private final ConcurrentMap<RegistrationKey, ProviderLifecycleSnapshot> lifecycleSnapshots =
            new ConcurrentHashMap<>();
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
        AtomicBoolean creator = new AtomicBoolean();
        AtomicBoolean disabled = new AtomicBoolean();
        AtomicReference<ProviderSlot> staleSlot = new AtomicReference<>();
        ProviderSlot slot;

        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            slot = activeDatabases.compute(key, (ignored, existing) -> {
                if (existing != null && existing.tryAcquireReference(ownerScope)) {
                    return existing;
                }
                if (existing != null) {
                    staleSlot.set(existing);
                }
                if (!configHandler.isDatabaseTypeEnabled(databaseType)) {
                    disabled.set(true);
                    return null;
                }
                creator.set(true);
                return new ProviderSlot(key, ownerScope);
            });
        } finally {
            readLock.unlock();
        }

        ProviderSlot stale = staleSlot.get();
        if (stale != null && stale != slot) {
            stale.close("replaced stale provider");
        }
        if (disabled.get()) {
            logger.error("Failed to establish connection for " + pluginId.value() + " with " + databaseType.name()
                    + ": This database type is disabled in the main config.");
            return null;
        }
        if (slot == null) {
            return null;
        }

        if (creator.get()) {
            slot.initialize();
        }

        ManagedDatabaseProvider provider = slot.awaitReady();
        if (provider == null) {
            detachFailedSlot(key, slot);
            Throwable failure = slot.failure();
            if (failure != null) {
                logger.error("Failed to register database for " + pluginId.value(), failure);
            }
            return null;
        }

        readLock.lock();
        try {
            ensureOpen();
            if (activeDatabases.get(key) != slot || slot.state() != ProviderLifecycleState.READY) {
                return null;
            }
            healthSnapshots.putIfAbsent(key, ConnectionHealthSnapshot.unprobed(true));
        } finally {
            readLock.unlock();
        }

        logger.info(pluginId.value() + " registered " + databaseType.name() + " connection ("
                + connectionIdentifier.value() + "), active references=" + slot.referenceCount());
        return provider;
    }

    private void detachFailedSlot(RegistrationKey key, ProviderSlot slot) {
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            activeDatabases.remove(key, slot);
            healthSnapshots.remove(key);
        } finally {
            readLock.unlock();
        }
    }

    protected DatabaseProvider getDatabase(String pluginName, DatabaseType databaseType, String connectionIdentifier) {
        return getDatabase(PluginId.of(pluginName), databaseType, ConnectionIdentifier.of(connectionIdentifier));
    }

    DatabaseProvider getDatabase(
            PluginId pluginId,
            DatabaseType databaseType,
            ConnectionIdentifier connectionIdentifier
    ) {
        Objects.requireNonNull(pluginId, "Plugin id cannot be null.");
        Objects.requireNonNull(databaseType, "Database type cannot be null.");
        Objects.requireNonNull(connectionIdentifier, "Connection identifier cannot be null.");
        return lookupProvider(new RegistrationKey(pluginId, databaseType, connectionIdentifier), null);
    }

    DatabaseProvider getDatabase(
            PluginId pluginId,
            OwnerScopeId ownerScope,
            DatabaseType databaseType,
            ConnectionIdentifier connectionIdentifier
    ) {
        Objects.requireNonNull(pluginId, "Plugin id cannot be null.");
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        Objects.requireNonNull(databaseType, "Database type cannot be null.");
        Objects.requireNonNull(connectionIdentifier, "Connection identifier cannot be null.");
        return lookupProvider(new RegistrationKey(pluginId, databaseType, connectionIdentifier), ownerScope);
    }

    private DatabaseProvider lookupProvider(RegistrationKey key, OwnerScopeId ownerScope) {
        ProviderSlot stale = null;
        DatabaseProvider result = null;
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            ProviderSlot slot = activeDatabases.get(key);
            if (slot == null || ownerScope != null && !slot.hasReference(ownerScope)) {
                return null;
            }
            if (slot.state() == ProviderLifecycleState.READY) {
                ManagedDatabaseProvider provider = slot.provider();
                if (provider != null && safeLocalState(provider, key)) {
                    result = provider;
                } else if (activeDatabases.remove(key, slot)) {
                    healthSnapshots.remove(key);
                    stale = slot;
                }
            }
        } finally {
            readLock.unlock();
        }
        if (stale != null) {
            stale.close("stale provider lookup");
            logger.warn("Removed stale provider for " + key + ".");
        }
        return result;
    }

    private boolean safeLocalState(ManagedDatabaseProvider provider, RegistrationKey key) {
        try {
            return provider.isLocallyConnected();
        } catch (RuntimeException e) {
            logger.warn("Provider local connection-state check failed for " + key + ".");
            return false;
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
        ProviderSlot toClose = null;
        ReferenceReleaseResult result;
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            ProviderSlot slot = activeDatabases.get(key);
            if (slot == null) {
                return;
            }
            result = slot.releaseReference(ownerScope);
            if (result.ownerHadReference() && result.totalReferences() == 0 && activeDatabases.remove(key, slot)) {
                healthSnapshots.remove(key);
                toClose = slot;
            }
        } finally {
            readLock.unlock();
        }

        if (!result.ownerHadReference()) {
            logger.warn(pluginId.value() + " attempted to release " + databaseType.name() + " connection ("
                    + connectionIdentifier.value() + ") from unregistered scope " + ownerScope.value());
            return;
        }
        if (toClose != null) {
            toClose.close("last reference released");
        }
    }

    protected void unregisterAllDatabases(String pluginName, String ownerScope) {
        unregisterAllDatabases(PluginId.of(pluginName), OwnerScopeId.of(ownerScope));
    }

    void unregisterAllDatabases(PluginId pluginId, OwnerScopeId ownerScope) {
        Objects.requireNonNull(pluginId, "Plugin id cannot be null.");
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        List<ProviderSlot> toClose = new ArrayList<>();
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            activeDatabases.forEach((key, slot) -> {
                if (key.pluginId().equals(pluginId)
                        && slot.releaseAllForOwner(ownerScope) == 0
                        && activeDatabases.remove(key, slot)) {
                    healthSnapshots.remove(key);
                    toClose.add(slot);
                }
            });
        } finally {
            readLock.unlock();
        }
        toClose.forEach(slot -> slot.close("owner scope released"));
    }

    protected void unregisterAllDatabasesForPlugin(String pluginName) {
        unregisterAllDatabasesForPlugin(PluginId.of(pluginName));
    }

    void unregisterAllDatabasesForPlugin(PluginId pluginId) {
        Objects.requireNonNull(pluginId, "Plugin id cannot be null.");
        List<ProviderSlot> toClose = new ArrayList<>();
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            activeDatabases.forEach((key, slot) -> {
                if (key.pluginId().equals(pluginId) && activeDatabases.remove(key, slot)) {
                    healthSnapshots.remove(key);
                    slot.forceReleaseAll();
                    toClose.add(slot);
                }
            });
        } finally {
            readLock.unlock();
        }
        toClose.forEach(slot -> slot.close("plugin cleanup"));
    }

    protected void shutdownAllDatabases() {
        List<ProviderSlot> toClose;
        Lock writeLock = lifecycleLock.writeLock();
        writeLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            toClose = new ArrayList<>(activeDatabases.values());
            activeDatabases.clear();
            healthSnapshots.clear();
        } finally {
            writeLock.unlock();
        }
        toClose.forEach(slot -> slot.close("registry shutdown"));
        logger.info("All database connections have been closed.");
    }

    protected ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> getActiveDatabases() {
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> snapshot = new ConcurrentHashMap<>();
            activeDatabases.forEach((key, slot) -> {
                if (slot.state() == ProviderLifecycleState.READY && slot.provider() != null) {
                    snapshot.put(key.toExternalKey(), slot.provider());
                }
            });
            return snapshot;
        } finally {
            readLock.unlock();
        }
    }

    protected Map<DatabaseConnectionKey, ConnectionHealthSnapshot> getCachedHealthSnapshots() {
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            Map<DatabaseConnectionKey, ConnectionHealthSnapshot> snapshots = new HashMap<>();
            activeDatabases.forEach((key, slot) -> {
                boolean connected = slot.state() == ProviderLifecycleState.READY;
                ConnectionHealthSnapshot cached = healthSnapshots.getOrDefault(
                        key,
                        ConnectionHealthSnapshot.unprobed(connected)
                );
                snapshots.put(key.toExternalKey(), new ConnectionHealthSnapshot(
                        connected
                                ? ConnectionHealthSnapshot.LocalConnectionState.CONNECTED
                                : ConnectionHealthSnapshot.LocalConnectionState.DISCONNECTED,
                        cached.remoteHealth(),
                        cached.checkedAt()
                ));
            });
            return Map.copyOf(snapshots);
        } finally {
            readLock.unlock();
        }
    }

    Map<DatabaseConnectionKey, ProviderLifecycleSnapshot> getProviderLifecycleSnapshots() {
        Map<DatabaseConnectionKey, ProviderLifecycleSnapshot> snapshots = new HashMap<>();
        lifecycleSnapshots.forEach((key, snapshot) -> snapshots.put(key.toExternalKey(), snapshot));
        return Map.copyOf(snapshots);
    }

    protected CompletableFuture<Void> probeRemoteHealthAsync() {
        Map<RegistrationKey, ProviderSlot> registrations;
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            registrations = new HashMap<>(activeDatabases);
        } finally {
            readLock.unlock();
        }
        return CompletableFuture.runAsync(() -> registrations.forEach((key, slot) -> {
            ManagedDatabaseProvider provider = slot.provider();
            if (slot.state() != ProviderLifecycleState.READY || provider == null) {
                return;
            }
            ConnectionHealthSnapshot.RemoteHealth health;
            try {
                health = provider.probeRemoteHealth()
                        ? ConnectionHealthSnapshot.RemoteHealth.HEALTHY
                        : ConnectionHealthSnapshot.RemoteHealth.UNHEALTHY;
            } catch (RuntimeException e) {
                health = ConnectionHealthSnapshot.RemoteHealth.ERROR;
            }
            if (activeDatabases.get(key) == slot && slot.state() == ProviderLifecycleState.READY) {
                healthSnapshots.put(key, new ConnectionHealthSnapshot(
                        ConnectionHealthSnapshot.LocalConnectionState.CONNECTED,
                        health,
                        Instant.now()
                ));
            }
        }));
    }

    protected Map<DatabaseConnectionKey, Integer> getActiveDatabaseReferenceCounts() {
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            Map<DatabaseConnectionKey, Integer> snapshot = new HashMap<>();
            activeDatabases.forEach((key, slot) -> snapshot.put(key.toExternalKey(), slot.referenceCount()));
            return snapshot;
        } finally {
            readLock.unlock();
        }
    }

    protected Map<DatabaseType, Boolean> getConfiguredDatabaseTypeStates() {
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            Map<DatabaseType, Boolean> states = new EnumMap<>(DatabaseType.class);
            for (DatabaseType type : DatabaseType.values()) {
                states.put(type, configHandler.isDatabaseTypeEnabled(type));
            }
            return states;
        } finally {
            readLock.unlock();
        }
    }

    protected String getOrmSchemaMode() {
        Lock readLock = lifecycleLock.readLock();
        readLock.lock();
        try {
            ensureOpen();
            return configHandler.getOrmSchemaMode();
        } finally {
            readLock.unlock();
        }
    }

    protected void reloadConfiguration() {
        Lock writeLock = lifecycleLock.writeLock();
        writeLock.lock();
        try {
            ensureOpen();
            ConfigHandler.ConfigSnapshot previousMainSnapshot = configHandler.currentSnapshot();
            DatabaseConfigMap.DatabaseConfigSnapshot previousDatabaseSnapshot = factory.currentConfigurationSnapshot();
            ConfigHandler.ConfigSnapshot mainSnapshot = configHandler.loadSnapshot();
            DatabaseConfigMap.DatabaseConfigSnapshot databaseSnapshot = factory.loadConfigurationSnapshot();
            configHandler.applySnapshot(mainSnapshot);
            factory.applyConfigurationSnapshot(databaseSnapshot);
            logger.info("Reloaded DataProvider configuration snapshot from disk: "
                    + describeMainConfigurationChanges(previousMainSnapshot, mainSnapshot)
                    + ", changed database files=" + previousDatabaseSnapshot.changedTypeCount(databaseSnapshot)
                    + ". Existing connections retain their previous settings until reconnected.");
        } catch (RuntimeException e) {
            logger.error("Rejected DataProvider configuration reload; active configuration remains unchanged.", e);
            throw e;
        } finally {
            writeLock.unlock();
        }
    }

    private static String describeMainConfigurationChanges(
            ConfigHandler.ConfigSnapshot previous,
            ConfigHandler.ConfigSnapshot current
    ) {
        int changedBackendCount = 0;
        for (DatabaseType type : DatabaseType.values()) {
            if (!previous.enabledTypes().get(type).equals(current.enabledTypes().get(type))) {
                changedBackendCount++;
            }
        }
        boolean schemaModeChanged = !previous.ormSchemaMode().equals(current.ormSchemaMode());
        return "changed backends=" + changedBackendCount + ", orm.schema_mode changed=" + schemaModeChanged;
    }

    protected boolean isClosed() {
        return closed;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(SHUTDOWN_MESSAGE);
        }
    }

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

    private final class ProviderSlot {
        private final RegistrationKey key;
        private final Map<OwnerScopeId, Integer> ownerReferenceCounts = new HashMap<>();
        private final AtomicReference<ProviderLifecycleState> state =
                new AtomicReference<>(ProviderLifecycleState.NEW);
        private final CompletableFuture<ManagedDatabaseProvider> readyFuture = new CompletableFuture<>();
        private final AtomicBoolean closeStarted = new AtomicBoolean();
        private final AtomicBoolean providerCloseStarted = new AtomicBoolean();
        private volatile ManagedDatabaseProvider provider;
        private volatile Throwable failure;
        private volatile Instant changedAt = Instant.now();
        private int referenceCount;

        private ProviderSlot(RegistrationKey key, OwnerScopeId initialOwnerScope) {
            this.key = Objects.requireNonNull(key, "Registration key cannot be null.");
            Objects.requireNonNull(initialOwnerScope, "Initial owner scope cannot be null.");
            referenceCount = 1;
            ownerReferenceCounts.put(initialOwnerScope, 1);
            publishSnapshot();
        }

        private void initialize() {
            if (!transition(ProviderLifecycleState.NEW, ProviderLifecycleState.CONNECTING)) {
                return;
            }
            try {
                ManagedDatabaseProvider created = factory.createDatabaseProvider(key.type(), key.connectionIdentifier());
                if (created == null) {
                    throw new IllegalStateException("Database factory returned null provider for " + key);
                }
                provider = created;
                created.connect();
                if (!created.isLocallyConnected()) {
                    Throwable providerFailure = created.lifecycleFailure();
                    if (providerFailure != null) {
                        throw new IllegalStateException("Provider connection failed for " + key, providerFailure);
                    }
                    throw new IllegalStateException("Provider did not become locally connected for " + key);
                }
                if (closeStarted.get() || closed
                        || !transition(ProviderLifecycleState.CONNECTING, ProviderLifecycleState.READY)) {
                    throw new IllegalStateException("Provider connection completed after closure started for " + key);
                }
                readyFuture.complete(created);
            } catch (Throwable connectFailure) {
                failure = connectFailure;
                if (closeStarted.get()) {
                    state.set(ProviderLifecycleState.CLOSING);
                    changedAt = Instant.now();
                    publishSnapshot();
                    closeProviderOnce(connectFailure);
                    state.set(ProviderLifecycleState.CLOSED);
                } else {
                    state.set(ProviderLifecycleState.FAILED);
                    closeProviderOnce(connectFailure);
                }
                changedAt = Instant.now();
                publishSnapshot();
                readyFuture.completeExceptionally(connectFailure);
            }
        }

        private ManagedDatabaseProvider awaitReady() {
            try {
                return readyFuture.join();
            } catch (CompletionException e) {
                return null;
            }
        }

        private synchronized boolean tryAcquireReference(OwnerScopeId ownerScope) {
            ProviderLifecycleState current = state.get();
            if (ownerScope == null || closeStarted.get()
                    || current == ProviderLifecycleState.CLOSING
                    || current == ProviderLifecycleState.CLOSED
                    || current == ProviderLifecycleState.FAILED) {
                return false;
            }
            if (current == ProviderLifecycleState.READY) {
                ManagedDatabaseProvider snapshot = provider;
                if (snapshot == null || !safeLocalState(snapshot, key)) {
                    return false;
                }
            }
            referenceCount++;
            ownerReferenceCounts.merge(ownerScope, 1, Integer::sum);
            return true;
        }

        private synchronized ReferenceReleaseResult releaseReference(OwnerScopeId ownerScope) {
            Integer count = ownerReferenceCounts.get(ownerScope);
            if (count == null || count <= 0 || referenceCount <= 0) {
                return new ReferenceReleaseResult(false, Math.max(referenceCount, 0));
            }
            if (count == 1) {
                ownerReferenceCounts.remove(ownerScope);
            } else {
                ownerReferenceCounts.put(ownerScope, count - 1);
            }
            referenceCount = Math.max(0, referenceCount - 1);
            return new ReferenceReleaseResult(true, referenceCount);
        }

        private synchronized int releaseAllForOwner(OwnerScopeId ownerScope) {
            Integer count = ownerReferenceCounts.remove(ownerScope);
            if (count != null && count > 0) {
                referenceCount = Math.max(0, referenceCount - count);
            }
            return referenceCount;
        }

        private synchronized boolean hasReference(OwnerScopeId ownerScope) {
            return ownerReferenceCounts.getOrDefault(ownerScope, 0) > 0;
        }

        private synchronized int referenceCount() {
            return Math.max(referenceCount, 0);
        }

        private synchronized void forceReleaseAll() {
            referenceCount = 0;
            ownerReferenceCounts.clear();
        }

        private void close(String reason) {
            if (!closeStarted.compareAndSet(false, true)) {
                return;
            }
            while (true) {
                ProviderLifecycleState current = state.get();
                if (current == ProviderLifecycleState.CLOSED || current == ProviderLifecycleState.CLOSING) {
                    return;
                }
                if (!state.compareAndSet(current, ProviderLifecycleState.CLOSING)) {
                    continue;
                }
                changedAt = Instant.now();
                publishSnapshot();
                readyFuture.completeExceptionally(
                        new IllegalStateException("Provider closed before ready: " + reason)
                );
                if (current == ProviderLifecycleState.CONNECTING) {
                    return;
                }
                closeProviderOnce(null);
                state.set(ProviderLifecycleState.CLOSED);
                changedAt = Instant.now();
                publishSnapshot();
                return;
            }
        }

        private void closeProviderOnce(Throwable originalFailure) {
            if (!providerCloseStarted.compareAndSet(false, true)) {
                return;
            }
            ManagedDatabaseProvider snapshot = provider;
            if (snapshot == null) {
                return;
            }
            try {
                snapshot.disconnect();
            } catch (Throwable closeFailure) {
                if (originalFailure != null) {
                    originalFailure.addSuppressed(closeFailure);
                } else {
                    failure = closeFailure;
                    logger.error("Failed to close provider for " + key, closeFailure);
                }
            }
        }

        private boolean transition(ProviderLifecycleState expected, ProviderLifecycleState update) {
            boolean changed = state.compareAndSet(expected, update);
            if (changed) {
                changedAt = Instant.now();
                publishSnapshot();
            }
            return changed;
        }

        private void publishSnapshot() {
            lifecycleSnapshots.put(key, new ProviderLifecycleSnapshot(state.get(), failure, changedAt));
        }

        private ProviderLifecycleState state() {
            return state.get();
        }

        private ManagedDatabaseProvider provider() {
            return provider;
        }

        private Throwable failure() {
            return failure;
        }
    }

    private record ReferenceReleaseResult(boolean ownerHadReference, int totalReferences) {
    }
}
