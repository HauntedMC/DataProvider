package nl.hauntedmc.dataprovider.orm.adapters;

import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.orm.adapters.sql.MySQLStorageAdapter;

import java.util.logging.Logger;

public class StorageAdapterFactory {

    /**
     * Returns the correct StorageAdapter based on the given DatabaseType.
     */
    public static StorageAdapter createAdapter(BaseDatabaseProvider databaseProvider, DatabaseType databaseType, Logger logger) {
        switch (databaseType) {
            case MYSQL:
                logger.info("Using MySQLStorageAdapter for database.");
                return new MySQLStorageAdapter((RelationalDataAccess) databaseProvider.getDataAccess());

            // Future database support
            case MONGODB:
                logger.warning("MongoDB support not implemented yet.");
                return null;

            case REDIS:
                logger.warning("Redis support not implemented yet.");
                return null;

            default:
                throw new IllegalArgumentException("Unsupported database type: " + databaseType);
        }
    }
}
