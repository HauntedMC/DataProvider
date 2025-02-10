package nl.hauntedmc.dataprovider.database.internal;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;
import nl.hauntedmc.dataprovider.database.document.impl.mongodb.MongoDBDatabase;
import nl.hauntedmc.dataprovider.database.keyvalue.impl.redis.RedisDatabase;
import nl.hauntedmc.dataprovider.database.relational.impl.mysql.MySQLDatabase;
import nl.hauntedmc.dataprovider.database.relational.impl.mariadb.MariaDBDatabase;
import nl.hauntedmc.dataprovider.database.relational.impl.postgresql.PostgreSQLDatabase;
import nl.hauntedmc.dataprovider.database.messaging.impl.rabbitmq.RabbitMQMessagingDatabase;
import nl.hauntedmc.dataprovider.database.messaging.impl.kafka.KafkaMessagingDatabase;
import nl.hauntedmc.dataprovider.database.messaging.impl.redis.RedisMessagingDatabase;
import nl.hauntedmc.dataprovider.logger.DPLogger;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Factory to create BaseDatabaseProvider instances based on the DatabaseType.
 */
class DatabaseFactory {

    protected static BaseDatabaseProvider createDatabaseProvider(DatabaseType type, String connectionIdentifier) {

        DatabaseConfigMap configManager = new DatabaseConfigMap();
        ConfigurationSection connectionConfig = configManager.getConfig(type, connectionIdentifier);

        if (connectionConfig == null) {
            DPLogger.error("Could not load configuration for " + connectionIdentifier + " (" + type.name() + ")");
            return null;
        }

        return switch (type) {
            case MYSQL -> new MySQLDatabase(connectionConfig);
            case MARIADB -> new MariaDBDatabase(connectionConfig);
            case POSTGRES -> new PostgreSQLDatabase(connectionConfig);
            case MONGODB -> new MongoDBDatabase(connectionConfig);
            case REDIS -> new RedisDatabase(connectionConfig);
            case RABBITMQ -> new RabbitMQMessagingDatabase(connectionConfig);
            case KAFKA -> new KafkaMessagingDatabase(connectionConfig);
            case REDIS_MESSAGING -> new RedisMessagingDatabase(connectionConfig);
        };
    }
}
