import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.api.orm.ORMContext;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;

import java.util.Optional;

/**
 * Example: MySQL registration + ORMContext creation.
 */
public final class RelationalOrmExample {

    private ORMContext ormContext;

    public void onEnable(DataProviderAPI api, ILoggerAdapter logger) {
        Optional<RelationalDatabaseProvider> relational = api.registerDatabaseAs(
                DatabaseType.MYSQL,
                "player_data_rw",
                RelationalDatabaseProvider.class
        );

        if (relational.isEmpty()) {
            logger.error("Could not initialize MySQL connection 'player_data_rw'.");
            return;
        }

        ormContext = new ORMContext(
                "my-plugin",
                relational.get().getDataSource(),
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
        api.unregisterDatabase(DatabaseType.MYSQL, "player_data_rw");
    }

    private static final class PlayerEntity {
    }

    private static final class PlayerProfileEntity {
    }
}
