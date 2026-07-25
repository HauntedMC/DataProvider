package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.api.DataProviderScope;
import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.api.orm.ORMContext;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.concurrent.ScopedDataSource;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;

import javax.sql.DataSource;
import java.util.Objects;

/** Public read-only facade for plugin-scoped DataProvider access. */
public final class DefaultDataProviderApi implements DataProviderAPI {

    private final DataProviderHandler handler;
    private final PluginIdentity identity;

    public DefaultDataProviderApi(DataProviderHandler handler) {
        this.handler = Objects.requireNonNull(handler, "DataProviderHandler cannot be null");
        this.identity = null;
    }

    private DefaultDataProviderApi(DataProviderHandler handler, PluginIdentity identity) {
        this.handler = Objects.requireNonNull(handler, "DataProviderHandler cannot be null");
        this.identity = Objects.requireNonNull(identity, "Plugin identity cannot be null");
    }

    @Override
    public DataProviderAPI forPlugin(Object platformPlugin) {
        return new DefaultDataProviderApi(handler, handler.issuePluginIdentity(platformPlugin));
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
                pluginId(), dataSource, logger, schemaMode, entityClasses);
    }

    /** Package-visible for API-path regression tests. */
    static boolean isManagedDataSource(DataSource dataSource) {
        return dataSource instanceof ScopedDataSource;
    }

    @Override
    public DatabaseProvider registerDatabaseOrThrow(DatabaseType databaseType, String connectionIdentifier) {
        PluginIdentity boundIdentity = requireIdentity();
        return wrapProvider(handler, boundIdentity,
                handler.registerDatabaseOrThrow(boundIdentity, databaseType, connectionIdentifier));
    }

    @Override
    public DataProviderScope scope(OwnerScope ownerScope) {
        PluginIdentity boundIdentity = requireIdentity();
        handler.requireIdentity(boundIdentity);
        return new DefaultDataProviderScope(handler, ownerScope, boundIdentity);
    }

    @Override
    public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        handler.unregisterDatabase(requireIdentity(), databaseType, connectionIdentifier);
    }

    @Override
    public void unregisterAllDatabases() {
        handler.unregisterAllDatabases(requireIdentity());
    }

    @Override
    public void unregisterAllDatabasesForPlugin() {
        handler.unregisterAllDatabasesForPlugin(requireIdentity());
    }

    @Override
    public DatabaseProvider requireRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        PluginIdentity boundIdentity = requireIdentity();
        return wrapProvider(handler, boundIdentity,
                handler.requireRegisteredDatabase(boundIdentity, databaseType, connectionIdentifier));
    }

    static DatabaseProvider wrapProvider(
            DataProviderHandler handler, PluginIdentity identity, DatabaseProvider provider
    ) {
        return IdentityBoundDatabaseProvider.wrap(handler, identity, provider);
    }

    private String pluginId() {
        return handler.getPluginId(requireIdentity());
    }

    private PluginIdentity requireIdentity() {
        if (identity == null) {
            throw new IllegalStateException("Bind DataProviderAPI with forPlugin(plugin) before use.");
        }
        return identity;
    }
}
