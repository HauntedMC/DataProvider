package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.api.DataProviderScope;
import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.api.orm.ORMContext;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.core.concurrent.ScopedDataSource;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;

import javax.sql.DataSource;
import java.util.Objects;

/** Public read-only facade for plugin-scoped DataProvider access. */
public final class DefaultDataProviderApi implements DataProviderAPI {

    private final DataProviderHandler handler;

    public DefaultDataProviderApi(DataProviderHandler handler) {
        this.handler = Objects.requireNonNull(handler, "DataProviderHandler cannot be null");
    }

    @Override
    public ORMContext createOrmContext(
            DataSource dataSource,
            nl.hauntedmc.dataprovider.logging.LoggerAdapter logger,
            String schemaMode,
            Class<?>... entityClasses
    ) {
        if (!isManagedDataSource(Objects.requireNonNull(dataSource, "DataSource cannot be null"))) {
            throw new IllegalArgumentException(
                    "ORMContext requires the scoped DataSource returned by a registered relational provider."
            );
        }
        return new nl.hauntedmc.dataprovider.core.orm.ORMContext(
                handler.getCurrentPluginId(), dataSource, logger, schemaMode, entityClasses);
    }

    /** Package-visible for API-path regression tests. */
    static boolean isManagedDataSource(DataSource dataSource) {
        return dataSource instanceof ScopedDataSource;
    }

    @Override
    public DatabaseProvider registerDatabaseOrThrow(DatabaseType databaseType, String connectionIdentifier) {
        String pluginId = handler.getCurrentPluginId();
        return wrapProvider(handler, pluginId, handler.registerDatabaseOrThrow(databaseType, connectionIdentifier));
    }

    @Override
    public DataProviderScope scope(OwnerScope ownerScope) {
        return new DefaultDataProviderScope(handler, ownerScope, handler.getCurrentPluginId());
    }

    @Override
    public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        handler.unregisterDatabase(databaseType, connectionIdentifier);
    }

    @Override
    public void unregisterAllDatabases() {
        handler.unregisterAllDatabases();
    }

    @Override
    public void unregisterAllDatabasesForPlugin() {
        handler.unregisterAllDatabasesForPlugin();
    }

    @Override
    public DatabaseProvider requireRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        String pluginId = handler.getCurrentPluginId();
        return wrapProvider(handler, pluginId, handler.requireRegisteredDatabase(databaseType, connectionIdentifier));
    }

    static DatabaseProvider wrapProvider(DataProviderHandler handler, String pluginId, DatabaseProvider provider) {
        return IdentityBoundDatabaseProvider.wrap(handler, pluginId, provider);
    }
}
