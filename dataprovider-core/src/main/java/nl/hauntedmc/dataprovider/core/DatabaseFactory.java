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
    private static final class PhysicalResource {
        private final ResourceKey key;
        private final ManagedDatabaseProvider provider;
        private final DataProviderExecutionRuntime.AdmissionLimits admissionLimits;
        private int leases = 1;
        private boolean retired;
        private ResourceAdmission admission;

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

        private synchronized void connect() {
            if (retired) {
                throw new IllegalStateException("Backend resource is closed: " + key);
            }
            if (!provider.isLocallyConnected()) {
                provider.connect();
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
            return new ScopedProvider(view, resourceExecution);
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

        private record ScopedProvider(DatabaseProvider view, ResourceExecutionHandle execution) {
        }
    }

    /** Lifecycle lease over a shared physical resource. */
    private static class SharedProviderLease implements ManagedDatabaseProvider {
        private final PhysicalResource resource;
        private final ExecutionHandle execution;
        private final ConcurrentMap<ResourceKey, PhysicalResource> resources;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile DatabaseProvider scoped;
        private volatile ResourceExecutionHandle resourceExecution;
        private volatile Throwable failure;

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
            } catch (RuntimeException exception) {
                failure = exception;
                disconnect();
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
        }

        @Override public boolean isConnected() { return isLocallyConnected(); }
        @Override public boolean isLocallyConnected() {
            DatabaseProvider view = scoped;
            return !closed.get() && view != null && view.isConnected();
        }
        @Override public boolean probeRemoteHealth() { return !closed.get() && resource.provider.probeRemoteHealth(); }
        @Override public Throwable lifecycleFailure() {
            return failure != null ? failure : resource.provider.lifecycleFailure();
        }
        @Override public nl.hauntedmc.dataprovider.database.DataAccess getDataAccess() {
            return view().getDataAccess();
        }
        @Override public javax.sql.DataSource getDataSource() { return view().getDataSource(); }

        protected DatabaseProvider view() {
            DatabaseProvider view = scoped;
            if (view == null || closed.get()) {
                throw new IllegalStateException("Database provider is not initialized.");
            }
            return view;
        }
    }

    private static final class RelationalLease extends SharedProviderLease implements RelationalDatabaseProvider {
        private RelationalLease(PhysicalResource r, ExecutionHandle e, ConcurrentMap<ResourceKey, PhysicalResource> rs) {
            super(r, e, rs);
        }
        @Override public nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess getDataAccess() {
            return ((RelationalDatabaseProvider) view()).getDataAccess();
        }
        @Override public nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager getSchemaManager() {
            return ((RelationalDatabaseProvider) view()).getSchemaManager();
        }
    }

    private static final class DocumentLease extends SharedProviderLease implements DocumentDatabaseProvider {
        private DocumentLease(PhysicalResource r, ExecutionHandle e, ConcurrentMap<ResourceKey, PhysicalResource> rs) {
            super(r, e, rs);
        }
        @Override public nl.hauntedmc.dataprovider.database.document.DocumentDataAccess getDataAccess() {
            return ((DocumentDatabaseProvider) view()).getDataAccess();
        }
    }

    private static final class KeyValueLease extends SharedProviderLease implements KeyValueDatabaseProvider {
        private KeyValueLease(PhysicalResource r, ExecutionHandle e, ConcurrentMap<ResourceKey, PhysicalResource> rs) {
            super(r, e, rs);
        }
        @Override public nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess getDataAccess() {
            return ((KeyValueDatabaseProvider) view()).getDataAccess();
        }
    }

    private static final class MessagingLease extends SharedProviderLease implements MessagingDatabaseProvider {
        private MessagingLease(PhysicalResource r, ExecutionHandle e, ConcurrentMap<ResourceKey, PhysicalResource> rs) {
            super(r, e, rs);
        }
        @Override public nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess getDataAccess() {
            return ((MessagingDatabaseProvider) view()).getDataAccess();
        }
    }
}
