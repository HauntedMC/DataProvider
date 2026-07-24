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
        RelationalDatabaseProvider relational = (RelationalDatabaseProvider) api.registerDatabaseOrThrow(
                DatabaseType.MYSQL, "example"
        );

        ormContext = api.createOrmContext(
                relational.getDataSource(),
                logger,
                "validate",
                PlayerEntity.class,
                PlayerProfileEntity.class
        );
    }

    public void onDisable(DataProviderAPI api) {
        if (ormContext != null) {
            ormContext.shutdown();
            ormContext = null;
        }
        api.unregisterDatabase(DatabaseType.MYSQL, "example");
    }

    private static final class PlayerEntity {
    }

    private static final class PlayerProfileEntity {
    }
}
