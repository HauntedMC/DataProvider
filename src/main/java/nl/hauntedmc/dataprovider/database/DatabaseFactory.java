package nl.hauntedmc.dataprovider.database;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;
import nl.hauntedmc.dataprovider.database.document.impl.mongodb.MongoDBDatabase;
import nl.hauntedmc.dataprovider.database.keyvalue.impl.redis.RedisDatabase;
import nl.hauntedmc.dataprovider.database.relational.impl.mysql.MySQLDatabase;
import nl.hauntedmc.dataprovider.database.relational.impl.mariadb.MariaDBDatabase;
import nl.hauntedmc.dataprovider.database.relational.impl.postgresql.PostgreSQLDatabase;
import nl.hauntedmc.dataprovider.database.messaging.impl.rabbitmq.RabbitMQMessagingDatabase;
import nl.hauntedmc.dataprovider.database.messaging.impl.kafka.KafkaMessagingDatabase;
import nl.hauntedmc.dataprovider.database.messaging.impl.redis.RedisMessagingDatabase;
import nl.hauntedmc.dataprovider.logging.DPLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Factory to create BaseDatabaseProvider instances based on the DatabaseType.
 */
public class DatabaseFactory {

    protected static BaseDatabaseProvider createDatabaseProvider(DatabaseType type, String connectionIdentifier) {
        DatabaseConfigManager configManager = DataProvider.getInstance().getDatabaseConfigManager();
        // Get only the section for the specified connection
        ConfigurationSection connectionConfig = configManager.getConfig(type, connectionIdentifier);

        if (connectionConfig == null) {
            DPLogger.error("Could not load configuration for " + connectionIdentifier + " (" + type.name() + ")");
            return null;
        }

        FileConfiguration config = (FileConfiguration) connectionConfig;

        return switch (type) {
            case MYSQL -> new MySQLDatabase(config);
            case MARIADB -> new MariaDBDatabase(config);
            case POSTGRES -> new PostgreSQLDatabase(config);
            case MONGODB -> new MongoDBDatabase(config);
            case REDIS -> new RedisDatabase(config);
            case RABBITMQ -> new RabbitMQMessagingDatabase(config);
            case KAFKA -> new KafkaMessagingDatabase(config);
            case REDIS_MESSAGING -> new RedisMessagingDatabase(config);
        };
    }
}
