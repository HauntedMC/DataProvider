package nl.hauntedmc.dataprovider.core;

import nl.hauntedmc.dataprovider.core.concurrent.ContextualExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.DataProviderExecutionRuntime;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.database.document.impl.mongodb.MongoDBDatabase;
import nl.hauntedmc.dataprovider.core.database.keyvalue.impl.redis.RedisDatabase;
import nl.hauntedmc.dataprovider.core.database.messaging.impl.redis.RedisMessagingDatabase;
import nl.hauntedmc.dataprovider.core.database.relational.impl.mysql.MySQLDatabase;
import nl.hauntedmc.dataprovider.core.exception.DataProviderExceptionMapper;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.util.Objects;
import java.util.function.Supplier;

class DatabaseFactory {

    private static final ThreadLocal<PluginId> CREATION_PLUGIN = new ThreadLocal<>();

    private final DatabaseConfigMap configMap;
    private final LoggerAdapter logger;
    private final DataProviderExecutionRuntime executionRuntime;

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
        ExecutionHandle rawExecution = executionRuntime == null
                ? ExecutionHandle.direct()
                : executionRuntime.openScope(pluginId.value(), type, connectionIdentifier.value());
        ExecutionHandle execution = new ContextualExecutionHandle(
                rawExecution,
                pluginId.value(),
                type,
                connectionIdentifier.value()
        );
        try {
            return switch (type) {
                case MYSQL -> new MySQLDatabase(connectionConfig, logger, execution);
                case MONGODB -> new MongoDBDatabase(connectionConfig, logger, execution);
                case REDIS -> new RedisDatabase(connectionConfig, logger, execution);
                case REDIS_MESSAGING -> new RedisMessagingDatabase(connectionConfig, logger, execution);
            };
        } catch (RuntimeException e) {
            execution.close();
            throw e;
        }
    }

    protected void shutdownExecutionRuntime() {
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
}
