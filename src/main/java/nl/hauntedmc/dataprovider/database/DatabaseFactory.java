package nl.hauntedmc.dataprovider.database;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.config.DatabaseConfigManager;
import nl.hauntedmc.dataprovider.database.impl.mongodb.MongoDBDatabase;
import nl.hauntedmc.dataprovider.database.impl.mysql.MySQLDatabase;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Factory to create DatabaseProvider instances based on the DatabaseType.
 */
public class DatabaseFactory {

    public static DatabaseProvider createDatabaseProvider(DatabaseType type) {
        DatabaseConfigManager configManager = DataProvider.getInstance().getDatabaseConfigManager();
        FileConfiguration config = configManager.getConfig(type);

        return switch (type) {
            case MYSQL -> new MySQLDatabase(config);
            case MONGODB -> new MongoDBDatabase(config);
            case REDIS -> throw new UnsupportedOperationException("Redis support is not yet implemented.");
        };
    }
}
