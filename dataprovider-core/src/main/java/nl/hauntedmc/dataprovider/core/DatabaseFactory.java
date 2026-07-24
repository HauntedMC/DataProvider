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

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

class DatabaseFactory {

    private static final ThreadLocal<PluginId> CREATION_PLUGIN = new ThreadLocal<>();

    private final DatabaseConfigMap configMap;
    private final LoggerAdapter logger;
    private final DataProviderExecutionRuntime executionRuntime;
    private final ConcurrentMap<ResourceKey, PhysicalResource> physicalResources = new ConcurrentHashMap<>();

    protected DatabaseFactory(DatabaseConfigMap configMap, LoggerAdapter logger) {
        this(configMap, logger, null);
    }

    protected DatabaseFactory(
            DatabaseConfigMap configMap,
            LoggerAdapter logger,
            DataProviderExecutionRuntime executionRuntime
    ) {
        this.configMap = Objects.requireNonNull(configMap, "Config map cannot be null.");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
        this.executionRuntime = executionRuntime;
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
        CommentedConfigurationNode connectionConfig = configMap.getConfig(type, connectionIdentifier);
        if (connectionConfig == null) {
            logger.error("Could not load configuration for " + connectionIdentifier.value() + " (" + type.name() + ")");
            throw DataProviderExceptionMapper.missingConfigurationFailure();
        }
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
            ResourceKey key = new ResourceKey(type, connectionIdentifier);
            PhysicalResource physical = physicalResources.compute(key, (ignored, existing) -> {
                if (existing != null && existing.retain()) {
                    return existing;
                }
                return new PhysicalResource(
                        key,
                        createPhysical(type, connectionConfig),
                        executionRuntime.admissionLimits(type)
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

    private ManagedDatabaseProvider createPhysical(DatabaseType type, CommentedConfigurationNode connectionConfig) {
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

    protected DatabaseConfigMap.DatabaseConfigSnapshot currentConfigurationSnapshot() {
        return configMap.currentSnapshot();
    }

    private record ResourceKey(DatabaseType type, ConnectionIdentifier identifier) {
    }

    /** One actual backend client/pool, reference counted by logical plugin providers. */
    private static final class PhysicalResource implements ManagedDatabaseProvider, ResilienceGateAware {
        private final ResourceKey key;
        private final ManagedDatabaseProvider provider;
        private final DataProviderExecutionRuntime.AdmissionLimits admissionLimits;
        private int leases = 1;
        private boolean retired;
        private long generation;
        private int consecutiveRecoveryFailures;
        private ResourceAdmission admission;
        private volatile java.util.function.BooleanSupplier resilienceGate = () -> true;
        private volatile java.util.function.Supplier<ConnectionHealthSnapshot> resilienceDiagnostics =
                () -> ConnectionHealthSnapshot.unprobed(isLocallyConnected());

        private PhysicalResource(
                ResourceKey key,
                ManagedDatabaseProvider provider,
                DataProviderExecutionRuntime.AdmissionLimits admissionLimits
        ) {
            this.key = key;
            this.provider = provider;
            this.admissionLimits = admissionLimits;
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
                    }
                    consecutiveRecoveryFailures = 0;
                } else {
                    consecutiveRecoveryFailures++;
                }
            }
            return healthy;
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
            return new ScopedProvider(view, resourceExecution, generation);
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

        private record ScopedProvider(DatabaseProvider view, ResourceExecutionHandle execution, long generation) {
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
                scopedGeneration = created.generation();
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
            DatabaseProvider view = scoped;
            if (view instanceof MessagingDatabaseProvider messaging) {
                try {
                    messaging.getDataAccess().shutdown().get(3, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Scope closure below prevents new work; the physical resource will close on its final lease.
                }
            }
            ResourceExecutionHandle scopedExecution = resourceExecution;
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
            PhysicalResource.ScopedProvider rebound = resource.scoped(execution);
            scoped = rebound.view();
            resourceExecution = rebound.execution();
            scopedGeneration = rebound.generation();
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
        private final nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess stableAccess = new StableMessagingAccess(this);
        private MessagingLease(PhysicalResource r, ExecutionHandle e, ConcurrentMap<ResourceKey, PhysicalResource> rs) {
            super(r, e, rs);
        }
        @Override public nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess getDataAccess() {
            return stableAccess;
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
        private StableMessagingAccess(SharedProviderLease lease) { super(lease); }
        private nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess delegate() { return ((MessagingDatabaseProvider) lease().view()).getDataAccess(); }
        @Override public <T extends nl.hauntedmc.dataprovider.database.messaging.api.EventMessage> java.util.concurrent.CompletableFuture<Void> publish(String destination, T message) { return call("publish", () -> delegate().publish(destination, message)); }
        @Override public <T extends nl.hauntedmc.dataprovider.database.messaging.api.EventMessage> nl.hauntedmc.dataprovider.database.messaging.api.Subscription subscribe(String destination, Class<T> type, java.util.function.Consumer<T> handler) { lease().requireAvailable("subscribe"); return delegate().subscribe(destination, type, handler); }
        @Override public java.util.concurrent.CompletableFuture<Void> shutdown() { return call("shutdown", () -> delegate().shutdown()); }
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
