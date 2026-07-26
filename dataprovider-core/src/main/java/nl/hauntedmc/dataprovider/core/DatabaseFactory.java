package nl.hauntedmc.dataprovider.core;

import nl.hauntedmc.dataprovider.core.concurrent.ContextualExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.DataProviderExecutionRuntime;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.ResourceAdmission;
import nl.hauntedmc.dataprovider.core.concurrent.ResourceExecutionHandle;
import nl.hauntedmc.dataprovider.core.database.document.impl.mongodb.MongoDBDatabase;
import nl.hauntedmc.dataprovider.core.database.keyvalue.impl.redis.RedisDatabase;
import nl.hauntedmc.dataprovider.core.database.messaging.impl.redis.RedisMessagingDatabase;
import nl.hauntedmc.dataprovider.core.database.relational.impl.mysql.MySQLDatabase;
import nl.hauntedmc.dataprovider.core.exception.DataProviderExceptionMapper;
import nl.hauntedmc.dataprovider.core.resilience.ConnectionHealthSnapshot;
import nl.hauntedmc.dataprovider.core.resilience.ResilienceGateAware;
import nl.hauntedmc.dataprovider.core.resilience.ResilienceTargetAware;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.Predicate;

class DatabaseFactory {

    private static final ThreadLocal<PluginId> CREATION_PLUGIN = new ThreadLocal<>();

    private final DatabaseConfigMap configMap;
    private final LoggerAdapter logger;
    private final DataProviderExecutionRuntime executionRuntime;
    private final Predicate<String> knownPlugin;
    private final ConcurrentMap<ResourceKey, PhysicalResource> physicalResources = new ConcurrentHashMap<>();

    protected DatabaseFactory(DatabaseConfigMap configMap, LoggerAdapter logger) {
        this(configMap, logger, null, pluginId -> true);
    }

    protected DatabaseFactory(
            DatabaseConfigMap configMap,
            LoggerAdapter logger,
            DataProviderExecutionRuntime executionRuntime
    ) {
        this(configMap, logger, executionRuntime, pluginId -> true);
    }

    protected DatabaseFactory(
            DatabaseConfigMap configMap,
            LoggerAdapter logger,
            DataProviderExecutionRuntime executionRuntime,
            Predicate<String> knownPlugin
    ) {
        this.configMap = Objects.requireNonNull(configMap, "Config map cannot be null.");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
        this.executionRuntime = executionRuntime;
        this.knownPlugin = Objects.requireNonNull(knownPlugin, "Known-plugin predicate cannot be null.");
    }

    static <T> T withCreationPlugin(PluginId pluginId, Supplier<T> action) {
        Objects.requireNonNull(pluginId, "Plugin id cannot be null.");
        Objects.requireNonNull(action, "Action cannot be null.");
        PluginId previous = CREATION_PLUGIN.get();
        CREATION_PLUGIN.set(pluginId);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CREATION_PLUGIN.remove();
            } else {
                CREATION_PLUGIN.set(previous);
            }
        }
    }

    protected ManagedDatabaseProvider createDatabaseProvider(DatabaseType type, String connectionIdentifier) {
        return createDatabaseProvider(type, ConnectionIdentifier.of(connectionIdentifier));
    }

    protected ManagedDatabaseProvider createDatabaseProvider(
            DatabaseType type,
            ConnectionIdentifier connectionIdentifier
    ) {
        PluginId pluginId = CREATION_PLUGIN.get();
        return createDatabaseProvider(pluginId == null ? PluginId.of("internal") : pluginId, type, connectionIdentifier);
    }

    protected ManagedDatabaseProvider createDatabaseProvider(
            PluginId pluginId,
            DatabaseType type,
            ConnectionIdentifier connectionIdentifier
    ) {
        Objects.requireNonNull(pluginId, "Plugin id cannot be null.");
        Objects.requireNonNull(type, "Database type cannot be null.");
        Objects.requireNonNull(connectionIdentifier, "Connection identifier cannot be null.");
        DatabaseConfigMap.AuthorizedConnection authorizedConnection = configMap.getAuthorizedConfig(
                type, connectionIdentifier, pluginId, knownPlugin
        );
        if (authorizedConnection == null) {
            logger.error("Could not load configuration for " + connectionIdentifier.value() + " (" + type.name() + ")");
            throw DataProviderExceptionMapper.missingConfigurationFailure();
        }
        CommentedConfigurationNode connectionConfig = authorizedConnection.config();
        if (executionRuntime == null) {
            return createPhysical(type, connectionConfig);
        }
        ExecutionHandle rawExecution = executionRuntime.openScope(pluginId.value(), type, connectionIdentifier.value());
        ExecutionHandle execution = new ContextualExecutionHandle(
                rawExecution,
                pluginId.value(),
                type,
                connectionIdentifier.value()
        );
        try {
            ResourceKey key = ResourceKey.forConnection(
                    pluginId,
                    type,
                    connectionIdentifier,
                    authorizedConnection.accessPolicy()
            );
            PhysicalResource physical = physicalResources.compute(key, (ignored, existing) -> {
                if (existing != null && existing.retain()) {
                    return existing;
                }
                return new PhysicalResource(
                        key,
                        createPhysical(type, connectionConfig),
                        executionRuntime.admissionLimits(type),
                        connectionFingerprint(connectionConfig)
                );
            });
            return switch (type) {
                case MYSQL -> new RelationalLease(physical, execution, physicalResources);
                case MONGODB -> new DocumentLease(physical, execution, physicalResources);
                case REDIS -> new KeyValueLease(physical, execution, physicalResources);
                case REDIS_MESSAGING -> new MessagingLease(physical, execution, physicalResources);
            };
        } catch (RuntimeException e) {
            execution.close();
            throw e;
        }
    }

    protected ManagedDatabaseProvider createPhysical(DatabaseType type, CommentedConfigurationNode connectionConfig) {
        return switch (type) {
            case MYSQL -> new MySQLDatabase(connectionConfig, logger, ExecutionHandle.direct());
            case MONGODB -> new MongoDBDatabase(connectionConfig, logger, ExecutionHandle.direct());
            case REDIS -> new RedisDatabase(connectionConfig, logger, ExecutionHandle.direct());
            case REDIS_MESSAGING -> new RedisMessagingDatabase(connectionConfig, logger, ExecutionHandle.direct());
        };
    }

    protected void shutdownExecutionRuntime() {
        physicalResources.forEach((key, resource) -> resource.forceClose());
        physicalResources.clear();
        if (executionRuntime != null) {
            executionRuntime.close();
        }
    }

    protected DatabaseConfigMap.DatabaseConfigSnapshot loadConfigurationSnapshot() {
        return configMap.loadSnapshot();
    }

    protected void applyConfigurationSnapshot(DatabaseConfigMap.DatabaseConfigSnapshot snapshot) {
        configMap.applySnapshot(snapshot);
    }

    /**
     * Builds and connects every changed physical resource before a configuration snapshot is made
     * active. The returned plan owns the replacement clients until it is committed or discarded.
     */
    protected PreparedConfigurationReload prepareConfigurationReload(
            DatabaseConfigMap.DatabaseConfigSnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "Database configuration snapshot cannot be null.");
        if (executionRuntime == null || physicalResources.isEmpty()) {
            return PreparedConfigurationReload.empty();
        }
        List<PreparedPhysicalReplacement> replacements = new ArrayList<>();
        try {
            for (PhysicalResource resource : physicalResources.values()) {
                CommentedConfigurationNode candidateConfig = configMap.getConfig(
                        snapshot, resource.key.type(), resource.key.identifier()
                );
                // A removed section will revoke its logical leases during reload. Do not attempt
                // to connect a resource that is about to be retired for policy/configuration removal.
                if (candidateConfig == null) {
                    continue;
                }
                String candidateFingerprint = connectionFingerprint(candidateConfig);
                if (!resource.hasFingerprint(candidateFingerprint)) {
                    ManagedDatabaseProvider candidate = null;
                    try {
                        candidate = createPhysical(resource.key.type(), candidateConfig);
                        candidate.connect();
                        if (!candidate.isLocallyConnected()) {
                            throw new IllegalStateException("Replacement provider did not become locally connected for "
                                    + resource.key, candidate.lifecycleFailure());
                        }
                        replacements.add(new PreparedPhysicalReplacement(resource, candidate, candidateFingerprint));
                    } catch (Throwable failure) {
                        if (candidate != null) {
                            try {
                                candidate.disconnect();
                            } catch (RuntimeException closeFailure) {
                                failure.addSuppressed(closeFailure);
                            }
                        }
                        throw failure;
                    }
                }
            }
            return new PreparedConfigurationReload(replacements);
        } catch (Throwable failure) {
            replacements.forEach(PreparedPhysicalReplacement::discard);
            throw failure;
        }
    }

    protected DatabaseConfigMap.DatabaseConfigSnapshot currentConfigurationSnapshot() {
        return configMap.currentSnapshot();
    }

    /** Returns false rather than exposing configuration errors while revalidating active registrations. */
    protected boolean isConnectionAuthorized(
            PluginId pluginId,
            DatabaseType type,
            ConnectionIdentifier connectionIdentifier
    ) {
        try {
            return configMap.isAuthorized(type, connectionIdentifier, pluginId, knownPlugin);
        } catch (ConnectionAccessDeniedException | InvalidConnectionAccessPolicyException denied) {
            logger.warn("Revoking " + pluginId.value() + " access to " + type.name() + "/"
                    + connectionIdentifier.value() + ": " + denied.getMessage());
            return false;
        }
    }

    /** Candidate-snapshot equivalent used to decide revocations before committing a reload. */
    protected boolean isConnectionAuthorized(
            DatabaseConfigMap.DatabaseConfigSnapshot snapshot,
            PluginId pluginId,
            DatabaseType type,
            ConnectionIdentifier connectionIdentifier
    ) {
        try {
            return configMap.isAuthorized(snapshot, type, connectionIdentifier, pluginId, knownPlugin);
        } catch (ConnectionAccessDeniedException | InvalidConnectionAccessPolicyException denied) {
            logger.warn("Revoking " + pluginId.value() + " access to " + type.name() + "/"
                    + connectionIdentifier.value() + ": " + denied.getMessage());
            return false;
        }
    }

    /**
     * Produces an opaque, deterministic digest of the connection-affecting configuration. Access
     * policy is intentionally excluded: policy changes affect leases, not the backend client. The
     * digest includes credentials without retaining or exposing their plaintext values.
     */
    static String connectionFingerprint(CommentedConfigurationNode connectionConfig) {
        Objects.requireNonNull(connectionConfig, "Connection configuration cannot be null.");
        StringBuilder canonical = new StringBuilder();
        appendFingerprintNode(canonical, connectionConfig, "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                encoded.append(Character.forDigit((value >>> 4) & 0xF, 16));
                encoded.append(Character.forDigit(value & 0xF, 16));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable for connection fingerprinting.", unavailable);
        }
    }

    private static void appendFingerprintNode(StringBuilder target, CommentedConfigurationNode node, String path) {
        List<java.util.Map.Entry<Object, CommentedConfigurationNode>> children = node.childrenMap().entrySet()
                .stream()
                .filter(entry -> !"access".equals(String.valueOf(entry.getKey())))
                .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .toList();
        if (!children.isEmpty()) {
            for (java.util.Map.Entry<Object, CommentedConfigurationNode> child : children) {
                String key = String.valueOf(child.getKey());
                appendFingerprintNode(target, child.getValue(), path + key + ".");
            }
            return;
        }
        List<? extends CommentedConfigurationNode> list = node.childrenList();
        if (!list.isEmpty()) {
            for (int index = 0; index < list.size(); index++) {
                appendFingerprintNode(target, list.get(index), path + index + ".");
            }
            return;
        }
        target.append(path).append('=').append(String.valueOf(node.raw())).append('\n');
    }

    protected static final class PreparedConfigurationReload implements AutoCloseable {
        private final List<PreparedPhysicalReplacement> replacements;
        private final AtomicBoolean completed = new AtomicBoolean();

        private PreparedConfigurationReload(List<PreparedPhysicalReplacement> replacements) {
            this.replacements = List.copyOf(replacements);
        }

        private static PreparedConfigurationReload empty() {
            return new PreparedConfigurationReload(List.of());
        }

        /** Atomically switches each individual resource after all replacements were validated. */
        protected void commit() {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            replacements.forEach(PreparedPhysicalReplacement::commit);
        }

        @Override
        public void close() {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            replacements.forEach(PreparedPhysicalReplacement::discard);
        }
    }

    private static final class PreparedPhysicalReplacement {
        private final PhysicalResource resource;
        private final ManagedDatabaseProvider replacement;
        private final String fingerprint;

        private PreparedPhysicalReplacement(
                PhysicalResource resource,
                ManagedDatabaseProvider replacement,
                String fingerprint
        ) {
            this.resource = resource;
            this.replacement = replacement;
            this.fingerprint = fingerprint;
        }

        private void commit() {
            if (!resource.replace(replacement, fingerprint)) {
                discard();
            }
        }

        private void discard() {
            try {
                replacement.disconnect();
            } catch (RuntimeException ignored) {
                // A rejected candidate must not prevent other candidates from being closed.
            }
        }
    }

    private record ResourceKey(DatabaseType type, ConnectionIdentifier identifier, PluginId pluginId) {
        private static ResourceKey forConnection(
                PluginId caller,
                DatabaseType type,
                ConnectionIdentifier identifier,
                ConnectionAccessPolicy accessPolicy
        ) {
            PluginId resourcePlugin = accessPolicy.isExplicitlyShared() ? accessPolicy.ownerPlugin() : caller;
            return new ResourceKey(type, identifier, resourcePlugin);
        }
    }

    /** One actual backend client/pool, reference counted by logical plugin providers. */
    private static final class PhysicalResource implements ManagedDatabaseProvider, ResilienceGateAware {
        private final ResourceKey key;
        private volatile ManagedDatabaseProvider provider;
        private final DataProviderExecutionRuntime.AdmissionLimits admissionLimits;
        private String configurationFingerprint;
        private int leases = 1;
        private boolean retired;
        private long generation;
        private int consecutiveRecoveryFailures;
        private ResourceAdmission admission;
        private final java.util.Set<SharedProviderLease> scopedLeases = ConcurrentHashMap.newKeySet();
        private volatile java.util.function.BooleanSupplier resilienceGate = () -> true;
        private volatile java.util.function.Supplier<ConnectionHealthSnapshot> resilienceDiagnostics =
                () -> ConnectionHealthSnapshot.unprobed(isLocallyConnected());

        private PhysicalResource(
                ResourceKey key,
                ManagedDatabaseProvider provider,
                DataProviderExecutionRuntime.AdmissionLimits admissionLimits,
                String configurationFingerprint
        ) {
            this.key = key;
            this.provider = provider;
            this.admissionLimits = admissionLimits;
            this.configurationFingerprint = configurationFingerprint;
        }

        private synchronized boolean hasFingerprint(String fingerprint) {
            return !retired && configurationFingerprint.equals(fingerprint);
        }

        /**
         * Installs a connected replacement as a new generation. The monitor protects the provider
         * reference and generation together, so every stable lease observes either the old complete
         * resource or the new complete resource. The old client is retired only after stable leases
         * have rebound (including their messaging subscription intent).
         */
        private boolean replace(ManagedDatabaseProvider replacement, String fingerprint) {
            ManagedDatabaseProvider retiredProvider;
            synchronized (this) {
                if (retired) {
                    return false;
                }
                if (configurationFingerprint.equals(fingerprint)) {
                    return false;
                }
                retiredProvider = provider;
                provider = replacement;
                configurationFingerprint = fingerprint;
                generation++;
                consecutiveRecoveryFailures = 0;
                admission = null;
            }
            scopedLeases.forEach(SharedProviderLease::rebindAfterPhysicalReplacement);
            try {
                retiredProvider.disconnect();
            } catch (RuntimeException ignored) {
                // The replacement is already live; an old pool close failure must not roll it back.
            }
            return true;
        }

        private synchronized boolean retain() {
            if (retired) {
                return false;
            }
            leases++;
            return true;
        }

        private synchronized boolean release() {
            if (retired) {
                return false;
            }
            leases--;
            if (leases > 0) {
                return false;
            }
            retired = true;
            provider.disconnect();
            return true;
        }

        private synchronized void forceClose() {
            if (!retired) {
                retired = true;
                provider.disconnect();
            }
        }

        @Override public synchronized void connect() {
            if (retired) {
                throw new IllegalStateException("Backend resource is closed: " + key);
            }
            if (!provider.isLocallyConnected()) {
                provider.connect();
                if (provider.isLocallyConnected()) {
                    generation++;
                }
            }
        }

        @Override public boolean recover() {
            boolean locallyInvalid;
            boolean recreate;
            boolean generationChanged = false;
            synchronized (this) {
                if (retired) {
                    return false;
                }
                locallyInvalid = !provider.isLocallyConnected();
                // Native pools recover ordinary transport interruptions themselves. A second
                // failed recovery proves that a locally "connected" pool is no longer usable,
                // so retire it before probing again rather than remaining stuck indefinitely.
                recreate = locallyInvalid || consecutiveRecoveryFailures > 0;
                if (recreate) {
                    // A locally invalid driver/pool cannot be trusted to release its retired resources
                    // when connect() is called again. Close it once before creating a replacement.
                    provider.disconnect();
                    provider.connect();
                    // Admission belongs to the retired physical resource too. A fresh admission gate prevents
                    // stale subscription permits from blocking the replacement client.
                    admission = null;
                }
            }
            // Do not hold the resource monitor during remote I/O: a CLOSED circuit still permits
            // ordinary work, and it must not queue behind a slow health validation.
            boolean healthy = provider.probeRemoteHealth();
            synchronized (this) {
                if (retired) {
                    return false;
                }
                if (healthy && provider.isLocallyConnected()) {
                    if (recreate) {
                        generation++;
                        generationChanged = true;
                    }
                    consecutiveRecoveryFailures = 0;
                } else {
                    consecutiveRecoveryFailures++;
                }
            }
            if (generationChanged) {
                // Resilience monitors target this shared resource directly. Notify every live
                // logical lease now, rather than waiting for an unrelated application call to
                // discover the new generation.
                scopedLeases.forEach(SharedProviderLease::rebindAfterPhysicalReplacement);
            }
            return healthy;
        }

        private void registerScopedLease(SharedProviderLease lease) {
            scopedLeases.add(lease);
        }

        private void unregisterScopedLease(SharedProviderLease lease) {
            scopedLeases.remove(lease);
        }

        private synchronized long generation() {
            return generation;
        }

        @Override public synchronized void disconnect() {
            forceClose();
        }

        @Override public synchronized boolean isConnected() {
            return isLocallyConnected();
        }

        @Override public synchronized boolean isLocallyConnected() {
            return !retired && provider.isLocallyConnected();
        }

        @Override public boolean probeRemoteHealth() {
            synchronized (this) {
                if (retired) {
                    return false;
                }
            }
            // See recover(): monitoring must never pin the logical-operation path behind I/O.
            return provider.probeRemoteHealth();
        }

        @Override public synchronized Throwable lifecycleFailure() {
            return provider.lifecycleFailure();
        }

        @Override public synchronized nl.hauntedmc.dataprovider.database.DataAccess getDataAccess() {
            return provider.getDataAccess();
        }

        @Override public synchronized javax.sql.DataSource getDataSource() {
            return provider.getDataSource();
        }

        @Override public void setResilienceGate(
                java.util.function.BooleanSupplier gate,
                java.util.function.Supplier<ConnectionHealthSnapshot> diagnostics
        ) {
            resilienceGate = Objects.requireNonNull(gate, "gate");
            resilienceDiagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        }

        @Override public void clearResilienceGate() {
            resilienceGate = () -> true;
            resilienceDiagnostics = () -> ConnectionHealthSnapshot.unprobed(isLocallyConnected());
        }

        @Override public void clearResilienceGateIfMatches(java.util.function.BooleanSupplier gate) {
            if (resilienceGate == gate) {
                clearResilienceGate();
            }
        }

        private void requireAvailable(String operation) {
            // A client can become locally invalid between scheduled probes. Never let that race
            // escape as an unstructured initialization error from a stale scoped view.
            if (!isLocallyConnected()) {
                throw DataProviderExceptionMapper.resilienceUnavailable(
                        key.type(), key.identifier().value(), operation, "UNAVAILABLE");
            }
            if (!resilienceGate.getAsBoolean()) {
                throw DataProviderExceptionMapper.resilienceUnavailable(
                        key.type(), key.identifier().value(), operation, resilienceDiagnostics.get().circuit().name());
            }
        }

        private synchronized ScopedProvider scoped(ExecutionHandle execution) {
            if (!provider.isLocallyConnected()) {
                throw new IllegalStateException("Backend resource is not connected: " + key);
            }
            if (admission == null) {
                admission = new ResourceAdmission(resourceCapacity(), subscriptionCapacity(), admissionLimits);
            }
            ResourceExecutionHandle resourceExecution = new ResourceExecutionHandle(execution, admission);
            DatabaseProvider view = switch (key.type()) {
                case MYSQL -> ((MySQLDatabase) provider).scoped(resourceExecution);
                case MONGODB -> ((MongoDBDatabase) provider).scoped(resourceExecution);
                case REDIS -> ((RedisDatabase) provider).scoped(resourceExecution);
                case REDIS_MESSAGING -> ((RedisMessagingDatabase) provider).scoped(resourceExecution);
            };
            return new ScopedProvider(view, resourceExecution, provider, generation);
        }

        private int resourceCapacity() {
            return switch (key.type()) {
                case MYSQL -> ((MySQLDatabase) provider).executionCapacity();
                case MONGODB -> ((MongoDBDatabase) provider).executionCapacity();
                case REDIS -> ((RedisDatabase) provider).executionCapacity();
                case REDIS_MESSAGING -> ((RedisMessagingDatabase) provider).executionCapacity();
            };
        }

        private int subscriptionCapacity() {
            return key.type() == DatabaseType.REDIS_MESSAGING
                    ? ((RedisMessagingDatabase) provider).subscriptionCapacity()
                    : 0;
        }

        private record ScopedProvider(
                DatabaseProvider view,
                ResourceExecutionHandle execution,
                ManagedDatabaseProvider provider,
                long generation
        ) {
        }
    }

    /** Lifecycle lease over a shared physical resource. */
    private static class SharedProviderLease implements ManagedDatabaseProvider, ResilienceTargetAware {
        private final PhysicalResource resource;
        private final ExecutionHandle execution;
        private final ConcurrentMap<ResourceKey, PhysicalResource> resources;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile DatabaseProvider scoped;
        private volatile ResourceExecutionHandle resourceExecution;
        private volatile ManagedDatabaseProvider scopedResourceProvider;
        private volatile Throwable failure;
        private volatile long scopedGeneration = -1;

        private SharedProviderLease(
                PhysicalResource resource,
                ExecutionHandle execution,
                ConcurrentMap<ResourceKey, PhysicalResource> resources
        ) {
            this.resource = resource;
            this.execution = execution;
            this.resources = resources;
        }

        @Override public synchronized void connect() {
            if (closed.get() || scoped != null) {
                return;
            }
            try {
                resource.connect();
                PhysicalResource.ScopedProvider created = resource.scoped(execution);
                scoped = created.view();
                resourceExecution = created.execution();
                scopedResourceProvider = created.provider();
                scopedGeneration = created.generation();
                resource.registerScopedLease(this);
            } catch (RuntimeException exception) {
                failure = exception;
                disconnect();
            }
        }

        @Override public synchronized boolean recover() {
            if (closed.get()) return false;
            try {
                // Native pools/drivers recover ordinary transport outages themselves. Rebuild only
                // when the local client has actually become invalid.
                boolean healthy = resource.recover();
                if (!healthy) {
                    return false;
                }
                refreshScopedViewIfNeeded();
                return true;
            } catch (RuntimeException failure) {
                this.failure = failure;
                return false;
            }
        }

        @Override public void disconnect() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            resource.unregisterScopedLease(this);
            onLeaseDisconnecting();
            DatabaseProvider view = scoped;
            if (view instanceof MessagingDatabaseProvider messaging) {
                try {
                    messaging.getDataAccess().shutdown().get(3, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Scope closure below prevents new work; the physical resource will close on its final lease.
                }
                try {
                    messaging.getDurableDataAccess().shutdown().get(3, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Pending durable entries are deliberately left for consumer-group reclaim after shutdown.
                }
            }
            ResourceExecutionHandle scopedExecution = resourceExecution;
            ManagedDatabaseProvider scopedProvider = scopedResourceProvider;
            if (scopedExecution != null && scopedProvider instanceof RedisMessagingDatabase redisMessaging) {
                redisMessaging.releaseScope(scopedExecution);
            }
            if (scopedExecution != null) {
                scopedExecution.close();
            } else {
                execution.close();
            }
            if (resource.release()) {
                resources.remove(resource.key, resource);
            }
            scoped = null;
            resourceExecution = null;
            scopedResourceProvider = null;
            scopedGeneration = -1;
        }

        @Override public boolean isConnected() { return isLocallyConnected(); }
        @Override public boolean isLocallyConnected() {
            DatabaseProvider view = scoped;
            return !closed.get() && view != null && view.isConnected();
        }
        @Override public boolean probeRemoteHealth() { return !closed.get() && resource.provider.probeRemoteHealth(); }
        @Override public ManagedDatabaseProvider resilienceTarget() { return resource; }

        private void requireAvailable(String operation) {
            if (closed.get()) {
                throw DataProviderExceptionMapper.providerClosed(
                        resource.key.type(), resource.key.identifier().value(), operation);
            }
            resource.requireAvailable(operation);
        }
        @Override public Throwable lifecycleFailure() {
            return failure != null ? failure : resource.provider.lifecycleFailure();
        }
        @Override public nl.hauntedmc.dataprovider.database.DataAccess getDataAccess() {
            return view().getDataAccess();
        }
        @Override public javax.sql.DataSource getDataSource() { return view().getDataSource(); }

        protected DatabaseProvider view() {
            refreshScopedViewIfNeeded();
            DatabaseProvider view = scoped;
            if (view == null || closed.get()) {
                throw new IllegalStateException("Database provider is not initialized.");
            }
            return view;
        }

        private synchronized void refreshScopedViewIfNeeded() {
            if (closed.get() || scopedGeneration == resource.generation()) {
                return;
            }
            DatabaseProvider previousView = scoped;
            ResourceExecutionHandle previousExecution = resourceExecution;
            ManagedDatabaseProvider previousProvider = scopedResourceProvider;
            PhysicalResource.ScopedProvider rebound = resource.scoped(execution);
            scoped = rebound.view();
            resourceExecution = rebound.execution();
            scopedResourceProvider = rebound.provider();
            scopedGeneration = rebound.generation();
            onScopedViewRecreated(rebound.view());
            retireScopedView(previousView, previousProvider, previousExecution);
        }

        private void rebindAfterPhysicalReplacement() {
            try {
                refreshScopedViewIfNeeded();
            } catch (RuntimeException rebindFailure) {
                // The new physical generation remains valid. Retain the failure on this one
                // logical lease so a later operation can retry its scoped-view construction.
                failure = rebindFailure;
            }
        }

        private void retireScopedView(
                DatabaseProvider previousView,
                ManagedDatabaseProvider previousProvider,
                ResourceExecutionHandle previousExecution
        ) {
            if (previousView instanceof MessagingDatabaseProvider messaging) {
                try {
                    messaging.getDataAccess().shutdown();
                } catch (RuntimeException ignored) {
                    // The replacement view is already attached; old listener cleanup is best effort.
                }
                try {
                    messaging.getDurableDataAccess().shutdown();
                } catch (RuntimeException ignored) {
                    // Durable entries remain reclaimable if an old consumer cannot stop immediately.
                }
            }
            if (previousExecution == null) {
                return;
            }
            if (previousProvider instanceof RedisMessagingDatabase redisMessaging) {
                redisMessaging.releaseScope(previousExecution);
            }
            previousExecution.releaseResource();
        }

        /**
         * Called after this lease has rebound its scoped view to a replacement physical resource.
         * Most access facades resolve their delegate per call; messaging additionally owns long-lived
         * logical consumers and therefore needs an explicit reattachment point.
         */
        protected void onScopedViewRecreated(DatabaseProvider rebound) {
            // No long-lived logical state for ordinary database access types.
        }

        /** Gives stable facades a chance to close logical state before their physical view is retired. */
        protected void onLeaseDisconnecting() {
            // No long-lived logical state for ordinary database access types.
        }
    }

    private static final class RelationalLease extends SharedProviderLease implements RelationalDatabaseProvider {
        private final nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess stableAccess = new StableRelationalAccess(this);
        private final nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager stableSchema = new StableSchemaManager(this);
        private final javax.sql.DataSource stableDataSource = new StableDataSource(this);
        private RelationalLease(PhysicalResource r, ExecutionHandle e, ConcurrentMap<ResourceKey, PhysicalResource> rs) {
            super(r, e, rs);
        }
        @Override public nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess getDataAccess() {
            return stableAccess;
        }
        @Override public nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager getSchemaManager() {
            return stableSchema;
        }
        @Override public javax.sql.DataSource getDataSource() { return stableDataSource; }
    }

    private static final class DocumentLease extends SharedProviderLease implements DocumentDatabaseProvider {
        private final nl.hauntedmc.dataprovider.database.document.DocumentDataAccess stableAccess = new StableDocumentAccess(this);
        private DocumentLease(PhysicalResource r, ExecutionHandle e, ConcurrentMap<ResourceKey, PhysicalResource> rs) {
            super(r, e, rs);
        }
        @Override public nl.hauntedmc.dataprovider.database.document.DocumentDataAccess getDataAccess() {
            return stableAccess;
        }
    }

    private static final class KeyValueLease extends SharedProviderLease implements KeyValueDatabaseProvider {
        private final nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess stableAccess = new StableKeyValueAccess(this);
        private KeyValueLease(PhysicalResource r, ExecutionHandle e, ConcurrentMap<ResourceKey, PhysicalResource> rs) {
            super(r, e, rs);
        }
        @Override public nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess getDataAccess() {
            return stableAccess;
        }
    }

    private static final class MessagingLease extends SharedProviderLease implements MessagingDatabaseProvider {
        private final StableMessagingAccess stableAccess = new StableMessagingAccess(this);
        private final StableDurableMessagingAccess stableDurableAccess = new StableDurableMessagingAccess(this);
        private MessagingLease(PhysicalResource r, ExecutionHandle e, ConcurrentMap<ResourceKey, PhysicalResource> rs) {
            super(r, e, rs);
        }
        @Override public nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess getDataAccess() {
            return stableAccess;
        }
        @Override public nl.hauntedmc.dataprovider.database.messaging.durable.DurableMessagingDataAccess getDurableDataAccess() {
            return stableDurableAccess;
        }

        @Override
        protected void onScopedViewRecreated(DatabaseProvider rebound) {
            MessagingDatabaseProvider messaging = (MessagingDatabaseProvider) rebound;
            stableAccess.reattach(messaging.getDataAccess());
            stableDurableAccess.reattach(messaging.getDurableDataAccess());
        }

        @Override
        protected void onLeaseDisconnecting() {
            stableAccess.shutdown();
            stableDurableAccess.shutdown();
        }
    }

    /** Stable logical access views resolve one current physical view per call and never replay work. */
    private abstract static class StableAccess {
        private final SharedProviderLease lease;

        private StableAccess(SharedProviderLease lease) {
            this.lease = Objects.requireNonNull(lease, "Lease cannot be null.");
        }

        final <T> java.util.concurrent.CompletableFuture<T> call(
                String operation,
                java.util.function.Supplier<java.util.concurrent.CompletableFuture<T>> action
        ) {
            try {
                lease.requireAvailable(operation);
                return action.get();
            } catch (RuntimeException failure) {
                java.util.concurrent.CompletableFuture<T> result = new java.util.concurrent.CompletableFuture<>();
                result.completeExceptionally(failure);
                return result;
            }
        }

        final SharedProviderLease lease() {
            return lease;
        }
    }

    private static final class StableRelationalAccess extends StableAccess
            implements nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess {
        private StableRelationalAccess(SharedProviderLease lease) { super(lease); }
        private nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess delegate() {
            return ((RelationalDatabaseProvider) lease().view()).getDataAccess();
        }
        @Override public java.util.concurrent.CompletableFuture<Void> executeUpdate(String query, Object... params) { return call("executeUpdate", () -> delegate().executeUpdate(query, params)); }
        @Override public java.util.concurrent.CompletableFuture<java.util.Map<String, Object>> queryForSingle(String query, Object... params) { return call("queryForSingle", () -> delegate().queryForSingle(query, params)); }
        @Override public java.util.concurrent.CompletableFuture<java.util.List<java.util.Map<String, Object>>> queryForList(String query, Object... params) { return call("queryForList", () -> delegate().queryForList(query, params)); }
        @Override public java.util.concurrent.CompletableFuture<Object> queryForSingleValue(String query, Object... params) { return call("queryForSingleValue", () -> delegate().queryForSingleValue(query, params)); }
        @Override public java.util.concurrent.CompletableFuture<Void> executeBatchUpdate(String query, java.util.List<Object[]> batch) { return call("executeBatchUpdate", () -> delegate().executeBatchUpdate(query, batch)); }
        @Override public <T> java.util.concurrent.CompletableFuture<T> executeTransactionally(nl.hauntedmc.dataprovider.database.relational.TransactionCallback<T> callback) { return call("executeTransactionally", () -> delegate().executeTransactionally(callback)); }
        @Override public java.util.concurrent.CompletableFuture<Object> executeInsert(String sql, Object[] parameters) { return call("executeInsert", () -> delegate().executeInsert(sql, parameters)); }
    }

    private static final class StableDocumentAccess extends StableAccess
            implements nl.hauntedmc.dataprovider.database.document.DocumentDataAccess {
        private StableDocumentAccess(SharedProviderLease lease) { super(lease); }
        private nl.hauntedmc.dataprovider.database.document.DocumentDataAccess delegate() { return ((DocumentDatabaseProvider) lease().view()).getDataAccess(); }
        @Override public java.util.concurrent.CompletableFuture<Void> insertOne(String collection, java.util.Map<String, Object> document) { return call("insertOne", () -> delegate().insertOne(collection, document)); }
        @Override public java.util.concurrent.CompletableFuture<java.util.Map<String, Object>> findOne(String collection, nl.hauntedmc.dataprovider.database.document.model.DocumentQuery query) { return call("findOne", () -> delegate().findOne(collection, query)); }
        @Override public java.util.concurrent.CompletableFuture<java.util.List<java.util.Map<String, Object>>> findMany(String collection, nl.hauntedmc.dataprovider.database.document.model.DocumentQuery query) { return call("findMany", () -> delegate().findMany(collection, query)); }
        @Override public java.util.concurrent.CompletableFuture<Void> updateOne(String collection, nl.hauntedmc.dataprovider.database.document.model.DocumentQuery query, nl.hauntedmc.dataprovider.database.document.model.DocumentUpdate update, nl.hauntedmc.dataprovider.database.document.model.DocumentUpdateOptions options) { return call("updateOne", () -> delegate().updateOne(collection, query, update, options)); }
        @Override public java.util.concurrent.CompletableFuture<Void> updateMany(String collection, nl.hauntedmc.dataprovider.database.document.model.DocumentQuery query, nl.hauntedmc.dataprovider.database.document.model.DocumentUpdate update, nl.hauntedmc.dataprovider.database.document.model.DocumentUpdateOptions options) { return call("updateMany", () -> delegate().updateMany(collection, query, update, options)); }
        @Override public java.util.concurrent.CompletableFuture<Void> deleteOne(String collection, nl.hauntedmc.dataprovider.database.document.model.DocumentQuery query) { return call("deleteOne", () -> delegate().deleteOne(collection, query)); }
        @Override public java.util.concurrent.CompletableFuture<Void> deleteMany(String collection, nl.hauntedmc.dataprovider.database.document.model.DocumentQuery query) { return call("deleteMany", () -> delegate().deleteMany(collection, query)); }
        @Override public java.util.concurrent.CompletableFuture<Void> createIndex(String collection, java.util.Map<String, Object> specification, java.util.Map<String, Object> options) { return call("createIndex", () -> delegate().createIndex(collection, specification, options)); }
        @Override public java.util.concurrent.CompletableFuture<Void> dropIndex(String collection, String index) { return call("dropIndex", () -> delegate().dropIndex(collection, index)); }
    }

    private static final class StableKeyValueAccess extends StableAccess
            implements nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess {
        private StableKeyValueAccess(SharedProviderLease lease) { super(lease); }
        private nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess delegate() { return ((KeyValueDatabaseProvider) lease().view()).getDataAccess(); }
        @Override public java.util.concurrent.CompletableFuture<Void> setKey(String key, String value) { return call("setKey", () -> delegate().setKey(key, value)); }
        @Override public java.util.concurrent.CompletableFuture<String> getKey(String key) { return call("getKey", () -> delegate().getKey(key)); }
        @Override public java.util.concurrent.CompletableFuture<Void> deleteKey(String key) { return call("deleteKey", () -> delegate().deleteKey(key)); }
        @Override public java.util.concurrent.CompletableFuture<java.util.List<java.util.Map<String, Object>>> queryByPattern(String pattern) { return call("queryByPattern", () -> delegate().queryByPattern(pattern)); }
        @Override public java.util.concurrent.CompletableFuture<Void> setKeyWithExpiry(String key, String value, int ttlSeconds) { return call("setKeyWithExpiry", () -> delegate().setKeyWithExpiry(key, value, ttlSeconds)); }
        @Override public java.util.concurrent.CompletableFuture<Void> pipelineSet(java.util.Map<String, String> entries) { return call("pipelineSet", () -> delegate().pipelineSet(entries)); }
        @Override public java.util.concurrent.CompletableFuture<Boolean> watchCompareAndSet(String key, String oldValue, String newValue) { return call("watchCompareAndSet", () -> delegate().watchCompareAndSet(key, oldValue, newValue)); }
        @Override public java.util.concurrent.CompletableFuture<Void> hset(String key, java.util.Map<String, String> fields) { return call("hset", () -> delegate().hset(key, fields)); }
        @Override public java.util.concurrent.CompletableFuture<java.util.Map<String, String>> hgetAll(String key) { return call("hgetAll", () -> delegate().hgetAll(key)); }
        @Override public java.util.concurrent.CompletableFuture<Void> hdel(String key, String... fields) { return call("hdel", () -> delegate().hdel(key, fields)); }
        @Override public java.util.concurrent.CompletableFuture<Void> sadd(String key, String... members) { return call("sadd", () -> delegate().sadd(key, members)); }
        @Override public java.util.concurrent.CompletableFuture<java.util.Set<String>> smembers(String key) { return call("smembers", () -> delegate().smembers(key)); }
        @Override public java.util.concurrent.CompletableFuture<Void> srem(String key, String... members) { return call("srem", () -> delegate().srem(key, members)); }
        @Override public java.util.concurrent.CompletableFuture<Void> zadd(String key, double score, String member) { return call("zadd", () -> delegate().zadd(key, score, member)); }
        @Override public java.util.concurrent.CompletableFuture<java.util.List<String>> zrangeByScore(String key, double minimum, double maximum) { return call("zrangeByScore", () -> delegate().zrangeByScore(key, minimum, maximum)); }
    }

    private static final class StableMessagingAccess extends StableAccess
            implements nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess {
        private final Object attachmentLock = new Object();
        private final java.util.concurrent.ConcurrentMap<String, LogicalSubscription<?>> subscriptions =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicBoolean shuttingDown = new java.util.concurrent.atomic.AtomicBoolean();

        private StableMessagingAccess(SharedProviderLease lease) { super(lease); }

        private nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess delegate() {
            return ((MessagingDatabaseProvider) lease().view()).getDataAccess();
        }

        @Override public <T extends nl.hauntedmc.dataprovider.database.messaging.api.EventMessage> java.util.concurrent.CompletableFuture<Void> publish(String destination, T message) { return call("publish", () -> delegate().publish(destination, message)); }

        @Override
        public <T extends nl.hauntedmc.dataprovider.database.messaging.api.EventMessage>
        nl.hauntedmc.dataprovider.database.messaging.api.Subscription subscribe(
                String destination,
                String messageType,
                Class<T> type,
                java.util.function.Consumer<T> handler
        ) {
            lease().requireAvailable("subscribe");
            synchronized (attachmentLock) {
                if (shuttingDown.get()) {
                    throw new IllegalStateException("Messaging access is shut down.");
                }
                nl.hauntedmc.dataprovider.database.messaging.api.Subscription physical =
                        delegate().subscribe(destination, messageType, type, handler);
                LogicalSubscription<T> logical = new LogicalSubscription<>(
                        physical.id(), destination, messageType, type, handler, physical);
                if (subscriptions.putIfAbsent(logical.id(), logical) != null) {
                    physical.unsubscribe();
                    throw new IllegalStateException("Duplicate logical Redis subscription id: " + logical.id());
                }
                return logical;
            }
        }

        @Override
        public java.util.List<nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionSnapshot> subscriptions() {
            return subscriptions.values().stream()
                    .map(LogicalSubscription::snapshot)
                    .toList();
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> shutdown() {
            if (!shuttingDown.compareAndSet(false, true)) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            java.util.concurrent.CompletableFuture<?>[] closes = subscriptions.values().stream()
                    .map(LogicalSubscription::unsubscribe)
                    .toArray(java.util.concurrent.CompletableFuture[]::new);
            return java.util.concurrent.CompletableFuture.allOf(closes)
                    .whenComplete((unused, failure) -> subscriptions.clear());
        }

        private void reattach(nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess replacement) {
            synchronized (attachmentLock) {
                if (shuttingDown.get()) {
                    return;
                }
                subscriptions.values().forEach(subscription -> subscription.reattach(replacement));
            }
        }

        private final class LogicalSubscription<T extends nl.hauntedmc.dataprovider.database.messaging.api.EventMessage>
                implements nl.hauntedmc.dataprovider.database.messaging.api.Subscription {
            private final String id;
            private final String destination;
            private final String messageType;
            private final Class<T> type;
            private final java.util.function.Consumer<T> handler;
            private final java.util.concurrent.atomic.AtomicReference<nl.hauntedmc.dataprovider.database.messaging.api.Subscription> physical;
            private final java.util.concurrent.atomic.AtomicLong attachment = new java.util.concurrent.atomic.AtomicLong();
            private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            private final java.util.concurrent.CompletableFuture<Void> completion = new java.util.concurrent.CompletableFuture<>();
            private final java.util.concurrent.atomic.AtomicReference<Throwable> terminalFailure = new java.util.concurrent.atomic.AtomicReference<>();

            private LogicalSubscription(
                    String id, String destination, String messageType, Class<T> type,
                    java.util.function.Consumer<T> handler,
                    nl.hauntedmc.dataprovider.database.messaging.api.Subscription initial
            ) {
                this.id = id;
                this.destination = destination;
                this.messageType = messageType;
                this.type = type;
                this.handler = handler;
                this.physical = new java.util.concurrent.atomic.AtomicReference<>(initial);
                watch(initial, attachment.incrementAndGet());
            }

            private void reattach(nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess replacement) {
                if (closed.get() || completion.isCompletedExceptionally()) return;
                // Fence the retired listener before creating the replacement: a late terminal
                // callback from the old pool must not win this reattachment race.
                long nextAttachment = attachment.incrementAndGet();
                try {
                    nl.hauntedmc.dataprovider.database.messaging.api.Subscription next =
                            replacement.subscribe(destination, messageType, type, handler);
                    physical.set(next);
                    watch(next, nextAttachment);
                } catch (Throwable failure) {
                    fail(failure);
                }
            }

            private void watch(nl.hauntedmc.dataprovider.database.messaging.api.Subscription candidate, long generation) {
                candidate.completion().whenComplete((unused, failure) -> {
                    // Completion from a listener on the retired pool must never terminate the logical handle.
                    if (attachment.get() != generation || physical.get() != candidate || closed.get()) return;
                    if (failure != null) fail(failure);
                });
            }

            private void fail(Throwable failure) {
                if (terminalFailure.compareAndSet(null, failure)) {
                    subscriptions.remove(id, this);
                    completion.completeExceptionally(failure);
                }
            }

            @Override public java.util.concurrent.CompletableFuture<Void> unsubscribe() {
                if (!closed.compareAndSet(false, true)) return completion;
                attachment.incrementAndGet();
                subscriptions.remove(id, this);
                nl.hauntedmc.dataprovider.database.messaging.api.Subscription current = physical.get();
                current.unsubscribe().whenComplete((unused, failure) -> {
                    if (failure == null) completion.complete(null); else completion.completeExceptionally(failure);
                });
                return completion;
            }
            @Override public String id() { return id; }
            @Override public nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionState state() {
                return closed.get() ? nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionState.CLOSED
                        : terminalFailure.get() != null ? nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionState.FAILED
                        : physical.get().state();
            }
            @Override public nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionSnapshot snapshot() {
                nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionSnapshot snapshot = physical.get().snapshot();
                return new nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionSnapshot(
                        id, destination, messageType, state(), snapshot.reconnectCount(), snapshot.generation(),
                        snapshot.lastFailureAt(), snapshot.lastFailure(), snapshot.currentDowntime(),
                        snapshot.totalDowntime(), snapshot.activeListener());
            }
            @Override public java.util.concurrent.CompletableFuture<Void> completion() { return completion; }
        }
    }

    private static final class StableDurableMessagingAccess extends StableAccess
            implements nl.hauntedmc.dataprovider.database.messaging.durable.DurableMessagingDataAccess {
        private final Object attachmentLock = new Object();
        private final java.util.concurrent.ConcurrentMap<String, LogicalDurableSubscription<?>> subscriptions =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.atomic.AtomicBoolean shuttingDown = new java.util.concurrent.atomic.AtomicBoolean();

        private StableDurableMessagingAccess(SharedProviderLease lease) { super(lease); }
        private nl.hauntedmc.dataprovider.database.messaging.durable.DurableMessagingDataAccess delegate() {
            return ((MessagingDatabaseProvider) lease().view()).getDurableDataAccess();
        }
        @Override public <T extends nl.hauntedmc.dataprovider.database.messaging.api.EventMessage> java.util.concurrent.CompletableFuture<nl.hauntedmc.dataprovider.database.messaging.durable.PublishedDurableEvent> publish(String stream, nl.hauntedmc.dataprovider.database.messaging.durable.DurableEvent<T> event) {
            return call("durablePublish", () -> delegate().publish(stream, event));
        }

        @Override public <T extends nl.hauntedmc.dataprovider.database.messaging.api.EventMessage> nl.hauntedmc.dataprovider.database.messaging.durable.DurableSubscription consume(String stream, String group, String consumer, String messageType, Class<T> type, java.util.function.Consumer<nl.hauntedmc.dataprovider.database.messaging.durable.DurableDelivery<T>> handler) {
            lease().requireAvailable("durableConsume");
            synchronized (attachmentLock) {
                if (shuttingDown.get()) throw new IllegalStateException("Durable messaging access is shut down.");
                nl.hauntedmc.dataprovider.database.messaging.durable.DurableSubscription physical =
                        delegate().consume(stream, group, consumer, messageType, type, handler);
                LogicalDurableSubscription<T> logical = new LogicalDurableSubscription<>(
                        physical.id(), stream, group, consumer, messageType, type, handler, physical);
                if (subscriptions.putIfAbsent(logical.id(), logical) != null) {
                    physical.closeAsync();
                    throw new IllegalStateException("A durable consumer already exists for " + logical.id());
                }
                return logical;
            }
        }
        @Override public java.util.List<nl.hauntedmc.dataprovider.database.messaging.durable.DurableSubscriptionSnapshot> subscriptions() {
            return subscriptions.values().stream().map(LogicalDurableSubscription::snapshot).toList();
        }
        @Override public java.util.concurrent.CompletableFuture<Void> shutdown() {
            if (!shuttingDown.compareAndSet(false, true)) return java.util.concurrent.CompletableFuture.completedFuture(null);
            java.util.concurrent.CompletableFuture<?>[] closes = subscriptions.values().stream()
                    .map(LogicalDurableSubscription::closeAsync)
                    .toArray(java.util.concurrent.CompletableFuture[]::new);
            return java.util.concurrent.CompletableFuture.allOf(closes)
                    .whenComplete((unused, failure) -> subscriptions.clear());
        }

        private void reattach(nl.hauntedmc.dataprovider.database.messaging.durable.DurableMessagingDataAccess replacement) {
            synchronized (attachmentLock) {
                if (shuttingDown.get()) return;
                subscriptions.values().forEach(subscription -> subscription.reattach(replacement));
            }
        }

        private final class LogicalDurableSubscription<T extends nl.hauntedmc.dataprovider.database.messaging.api.EventMessage>
                implements nl.hauntedmc.dataprovider.database.messaging.durable.DurableSubscription {
            private final String id;
            private final String stream;
            private final String group;
            private final String consumer;
            private final String messageType;
            private final Class<T> type;
            private final java.util.function.Consumer<nl.hauntedmc.dataprovider.database.messaging.durable.DurableDelivery<T>> handler;
            private final java.util.concurrent.atomic.AtomicReference<nl.hauntedmc.dataprovider.database.messaging.durable.DurableSubscription> physical;
            private final java.util.concurrent.atomic.AtomicLong attachment = new java.util.concurrent.atomic.AtomicLong();
            private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
            private final java.util.concurrent.CompletableFuture<Void> completion = new java.util.concurrent.CompletableFuture<>();

            private LogicalDurableSubscription(String id, String stream, String group, String consumer, String messageType,
                    Class<T> type, java.util.function.Consumer<nl.hauntedmc.dataprovider.database.messaging.durable.DurableDelivery<T>> handler,
                    nl.hauntedmc.dataprovider.database.messaging.durable.DurableSubscription initial) {
                this.id = id; this.stream = stream; this.group = group; this.consumer = consumer; this.messageType = messageType;
                this.type = type; this.handler = handler;
                this.physical = new java.util.concurrent.atomic.AtomicReference<>(initial);
                watch(initial, attachment.incrementAndGet());
            }

            private void reattach(nl.hauntedmc.dataprovider.database.messaging.durable.DurableMessagingDataAccess replacement) {
                if (closed.get() || completion.isCompletedExceptionally()) return;
                // Fence the retired consumer loop before starting its replacement.
                long nextAttachment = attachment.incrementAndGet();
                try {
                    nl.hauntedmc.dataprovider.database.messaging.durable.DurableSubscription next =
                            replacement.consume(stream, group, consumer, messageType, type, handler);
                    physical.set(next);
                    watch(next, nextAttachment);
                } catch (Throwable failure) {
                    subscriptions.remove(id, this);
                    completion.completeExceptionally(failure);
                }
            }

            private void watch(nl.hauntedmc.dataprovider.database.messaging.durable.DurableSubscription candidate, long generation) {
                candidate.completion().whenComplete((unused, failure) -> {
                    // Retired consumer loops complete as the old transport closes; that is not a logical close.
                    if (attachment.get() != generation || physical.get() != candidate || closed.get()) return;
                    if (failure != null) {
                        subscriptions.remove(id, this);
                        completion.completeExceptionally(failure);
                    }
                });
            }

            @Override public String id() { return id; }
            @Override public nl.hauntedmc.dataprovider.database.messaging.durable.DurableSubscriptionSnapshot snapshot() {
                nl.hauntedmc.dataprovider.database.messaging.durable.DurableSubscriptionSnapshot snapshot = physical.get().snapshot();
                return new nl.hauntedmc.dataprovider.database.messaging.durable.DurableSubscriptionSnapshot(
                        id, stream, group, consumer, !closed.get() && snapshot.active(), snapshot.pendingCount(), snapshot.lag(),
                        snapshot.deliveredCount(), snapshot.acknowledgedCount(), snapshot.reclaimedCount(),
                        snapshot.deadLetteredCount(), snapshot.lastFailure());
            }
            @Override public java.util.concurrent.CompletableFuture<Void> closeAsync() {
                if (!closed.compareAndSet(false, true)) return completion;
                attachment.incrementAndGet();
                subscriptions.remove(id, this);
                physical.get().closeAsync().whenComplete((unused, failure) -> {
                    if (failure == null) completion.complete(null); else completion.completeExceptionally(failure);
                });
                return completion;
            }
            @Override public java.util.concurrent.CompletableFuture<Void> completion() { return completion; }
        }
    }

    private static final class StableSchemaManager extends StableAccess
            implements nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager {
        private StableSchemaManager(SharedProviderLease lease) { super(lease); }
        private nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager delegate() { return ((RelationalDatabaseProvider) lease().view()).getSchemaManager(); }
        @Override public java.util.concurrent.CompletableFuture<Void> createTable(nl.hauntedmc.dataprovider.database.relational.schema.TableDefinition definition) { return call("createTable", () -> delegate().createTable(definition)); }
        @Override public java.util.concurrent.CompletableFuture<Void> alterTable(nl.hauntedmc.dataprovider.database.relational.schema.TableDefinition definition) { return call("alterTable", () -> delegate().alterTable(definition)); }
        @Override public java.util.concurrent.CompletableFuture<Void> dropTable(String table) { return call("dropTable", () -> delegate().dropTable(table)); }
        @Override public java.util.concurrent.CompletableFuture<Boolean> tableExists(String table) { return call("tableExists", () -> delegate().tableExists(table)); }
        @Override public java.util.concurrent.CompletableFuture<Void> addIndex(String table, String column, boolean unique) { return call("addIndex", () -> delegate().addIndex(table, column, unique)); }
        @Override public java.util.concurrent.CompletableFuture<Void> removeIndex(String table, String index) { return call("removeIndex", () -> delegate().removeIndex(table, index)); }
        @Override public java.util.concurrent.CompletableFuture<Void> addForeignKey(String table, String column, String referenceTable, String referenceColumn) { return call("addForeignKey", () -> delegate().addForeignKey(table, column, referenceTable, referenceColumn)); }
        @Override public java.util.concurrent.CompletableFuture<Void> removeForeignKey(String table, String constraint) { return call("removeForeignKey", () -> delegate().removeForeignKey(table, constraint)); }
    }

    /** A stable facade over a replaceable JDBC pool. */
    private static final class StableDataSource implements nl.hauntedmc.dataprovider.core.concurrent.ScopedDataSource {
        private final SharedProviderLease lease;

        private StableDataSource(SharedProviderLease lease) {
            this.lease = Objects.requireNonNull(lease, "Lease cannot be null.");
        }

        private javax.sql.DataSource delegate() {
            return ((RelationalDatabaseProvider) lease.view()).getDataSource();
        }

        private void requireAvailable() throws java.sql.SQLTransientConnectionException {
            try {
                lease.requireAvailable("getConnection");
            } catch (RuntimeException failure) {
                throw new java.sql.SQLTransientConnectionException("The backend circuit is open.", failure);
            }
        }

        @Override public java.sql.Connection getConnection() throws java.sql.SQLException { requireAvailable(); return delegate().getConnection(); }
        @Override public java.sql.Connection getConnection(String username, String password) throws java.sql.SQLException { requireAvailable(); return delegate().getConnection(username, password); }
        @Override public java.io.PrintWriter getLogWriter() throws java.sql.SQLException { return delegate().getLogWriter(); }
        @Override public void setLogWriter(java.io.PrintWriter writer) throws java.sql.SQLException { delegate().setLogWriter(writer); }
        @Override public void setLoginTimeout(int seconds) throws java.sql.SQLException { delegate().setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() throws java.sql.SQLException { return delegate().getLoginTimeout(); }
        @Override public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException { return delegate().getParentLogger(); }
        @Override public <T> T unwrap(Class<T> type) throws java.sql.SQLException {
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            // The current scoped DataSource is replaceable after recovery. Returning it would
            // strand callers on a retired pool and let them bypass the stable availability gate.
            throw new java.sql.SQLException("The physical DataSource is not exposed by a stable provider.");
        }
        @Override public boolean isWrapperFor(Class<?> type) { return type.isInstance(this); }
    }
}
