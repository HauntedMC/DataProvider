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
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Factory to create BaseDatabaseProvider instances based on the DatabaseType.
 */
public class DatabaseFactory {

    public static BaseDatabaseProvider createDatabaseProvider(DatabaseType type) {
        DatabaseConfigManager configManager = DataProvider.getInstance().getDatabaseConfigManager();
        FileConfiguration config = configManager.getConfig(type);

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
