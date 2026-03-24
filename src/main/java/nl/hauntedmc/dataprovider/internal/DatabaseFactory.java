package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.document.impl.mongodb.MongoDBDatabase;
import nl.hauntedmc.dataprovider.database.keyvalue.impl.redis.RedisDatabase;
import nl.hauntedmc.dataprovider.database.messaging.impl.redis.RedisMessagingDatabase;
import nl.hauntedmc.dataprovider.database.relational.impl.mysql.MySQLDatabase;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.util.Objects;

class DatabaseFactory {

    private final DatabaseConfigMap configMap;
    private final ILoggerAdapter logger;

    protected DatabaseFactory(DatabaseConfigMap configMap, ILoggerAdapter logger) {
        this.configMap = configMap;
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    protected DatabaseProvider createDatabaseProvider(DatabaseType type, String connectionIdentifier) {
        CommentedConfigurationNode connectionConfig = configMap.getConfig(type, connectionIdentifier);
        if (connectionConfig == null) {
            logger.error("Could not load configuration for " + connectionIdentifier + " (" + type.name() + ")");
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
