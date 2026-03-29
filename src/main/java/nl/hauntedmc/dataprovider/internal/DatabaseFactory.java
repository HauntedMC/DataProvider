package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.document.impl.mongodb.MongoDBDatabase;
import nl.hauntedmc.dataprovider.database.keyvalue.impl.redis.RedisDatabase;
import nl.hauntedmc.dataprovider.database.messaging.impl.redis.RedisMessagingDatabase;
import nl.hauntedmc.dataprovider.database.relational.impl.mysql.MySQLDatabase;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.util.Objects;

class DatabaseFactory {

    private final DatabaseConfigMap configMap;
    private final LoggerAdapter logger;

    protected DatabaseFactory(DatabaseConfigMap configMap, LoggerAdapter logger) {
        this.configMap = Objects.requireNonNull(configMap, "Config map cannot be null.");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    protected ManagedDatabaseProvider createDatabaseProvider(DatabaseType type, String connectionIdentifier) {
        return createDatabaseProvider(type, ConnectionIdentifier.of(connectionIdentifier));
    }

    protected ManagedDatabaseProvider createDatabaseProvider(
            DatabaseType type,
            ConnectionIdentifier connectionIdentifier
    ) {
        Objects.requireNonNull(type, "Database type cannot be null.");
        Objects.requireNonNull(connectionIdentifier, "Connection identifier cannot be null.");
        CommentedConfigurationNode connectionConfig = configMap.getConfig(type, connectionIdentifier);
        if (connectionConfig == null) {
            logger.error("Could not load configuration for " + connectionIdentifier.value() + " (" + type.name() + ")");
            return null;
        }
        return switch (type) {
            case MYSQL -> new MySQLDatabase(connectionConfig, logger);
            case MONGODB -> new MongoDBDatabase(connectionConfig, logger);
            case REDIS -> new RedisDatabase(connectionConfig, logger);
            case REDIS_MESSAGING -> new RedisMessagingDatabase(connectionConfig, logger);
        };
    }
}
