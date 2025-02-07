package nl.hauntedmc.dataprovider.database;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.config.DatabaseConfigManager;
import nl.hauntedmc.dataprovider.database.impl.MySQLDatabase;
import org.bukkit.configuration.file.FileConfiguration;

public class DatabaseFactory {

    public static DatabaseProvider createDatabaseProvider(DatabaseType type) {
        DatabaseConfigManager configManager = new DatabaseConfigManager(DataProvider.getInstance());
        FileConfiguration config = configManager.getConfig(type);

        return switch (type) {
            case MYSQL -> new MySQLDatabase(config);
            case MONGODB -> throw new UnsupportedOperationException("MongoDB support is not yet implemented.");
            case REDIS -> throw new UnsupportedOperationException("Redis support is not yet implemented.");
            default -> throw new IllegalArgumentException("Unknown database type: " + type);
        };
    }
}
