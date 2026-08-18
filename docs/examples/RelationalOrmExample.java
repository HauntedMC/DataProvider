import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.api.orm.ORMContext;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;


/**
 * Example: MySQL registration + ORMContext creation.
 */
public final class RelationalOrmExample {

    private ORMContext ormContext;

    public void onEnable(DataProviderAPI api, LoggerAdapter logger) {
        RelationalDatabaseProvider relational = api.registerDatabaseOrThrow(
                DatabaseType.MYSQL, "example", RelationalDatabaseProvider.class
        );
        try {
            ormContext = api.createConfiguredOrmContext(
                    relational.getDataSource(),
                    logger,
                    PlayerEntity.class,
                    PlayerProfileEntity.class
            );
        } catch (RuntimeException exception) {
            api.unregisterDatabase(DatabaseType.MYSQL, "example");
            throw exception;
        }
    }

    public void onDisable(DataProviderAPI api) {
        ORMContext current = ormContext;
        ormContext = null;
        try {
            if (current != null) {
                current.close();
            }
        } finally {
            api.unregisterDatabase(DatabaseType.MYSQL, "example");
        }
    }

    private static final class PlayerEntity {
    }

    private static final class PlayerProfileEntity {
    }
}
