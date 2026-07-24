package nl.hauntedmc.dataprovider.api;

import nl.hauntedmc.dataprovider.api.orm.ORMContext;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;

import javax.sql.DataSource;

/** Public, platform-neutral facade for plugin-scoped database registrations. */
public interface DataProviderAPI {

    ORMContext createOrmContext(
            DataSource dataSource,
            LoggerAdapter logger,
            String schemaMode,
            Class<?>... entityClasses
    );

    /** Registers a database or throws a structured public exception retaining the failure category. */
    DatabaseProvider registerDatabaseOrThrow(DatabaseType databaseType, String connectionIdentifier);

    DataProviderScope scope(OwnerScope ownerScope);

    void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier);

    void unregisterAllDatabases();

    void unregisterAllDatabasesForPlugin();

    /** Returns a registered provider or throws a structured registration-state failure. */
    DatabaseProvider requireRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier);

    default DataProviderScope scope(String ownerScope) {
        return scope(OwnerScope.of(ownerScope));
    }

}
