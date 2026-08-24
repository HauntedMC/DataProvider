package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.api.DataProviderScope;
import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.api.observation.DataProviderObserver;
import nl.hauntedmc.dataprovider.api.observation.DataProviderOperationContext;
import nl.hauntedmc.dataprovider.api.orm.ORMContext;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.Objects;

/** Public read-only facade for plugin-scoped DataProvider access. */
public final class DefaultDataProviderApi implements DataProviderAPI {

    private final DataProviderHandler handler;
    private final PluginIdentity identity;
    private final DataProviderObserver observer;

    public DefaultDataProviderApi(DataProviderHandler handler) {
        this(handler, null, DataProviderObserver.noop());
    }

    private DefaultDataProviderApi(
            DataProviderHandler handler,
            PluginIdentity identity,
            DataProviderObserver observer
    ) {
        this.handler = Objects.requireNonNull(handler, "DataProviderHandler cannot be null");
        this.identity = identity;
        this.observer = Objects.requireNonNull(observer, "DataProvider observer cannot be null.");
    }

    @Override
    public DataProviderAPI forPlugin(Object platformPlugin) {
        return new DefaultDataProviderApi(handler, handler.issuePluginIdentity(platformPlugin), observer);
    }

    @Override
    public DataProviderAPI withObserver(DataProviderObserver dataProviderObserver) {
        return new DefaultDataProviderApi(
                handler,
                identity,
                Objects.requireNonNull(dataProviderObserver, "DataProvider observer cannot be null.")
        );
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
        PluginIdentity boundIdentity = requireIdentity();
        Class<?>[] validatedEntities = validateEntityClasses(boundIdentity, entityClasses);
        String pluginId = handler.getPluginId(boundIdentity);
        ORMContext context = new nl.hauntedmc.dataprovider.core.orm.ORMContext(
                pluginId,
                dataSource,
                logger,
                handler.getConfiguredOrmSchemaMode(boundIdentity),
                validatedEntities
        );
        if (!DataProviderObservations.isEnabled(observer)) {
            return context;
        }
        return new ObservedOrmContext(
                context,
                observer,
                operationContext(
                        pluginId,
                        IdentityBoundDatabaseProvider.boundOwnerScope(dataSource),
                        IdentityBoundDatabaseProvider.boundDatabaseType(dataSource),
                        "orm.runInTransaction"
                )
        );
    }

    /** Package-visible for API-path regression tests. */
    static boolean isManagedDataSource(DataSource dataSource) {
        return IdentityBoundDatabaseProvider.isBoundDataSource(dataSource);
    }

    static Class<?>[] validateEntityClasses(PluginIdentity identity, Class<?>[] entityClasses) {
        Objects.requireNonNull(identity, "Plugin identity cannot be null.");
        if (entityClasses == null || entityClasses.length == 0) {
            throw new IllegalArgumentException("At least one entity class must be provided.");
        }
        Class<?>[] validated = Arrays.copyOf(entityClasses, entityClasses.length);
        for (Class<?> entityClass : validated) {
            Objects.requireNonNull(entityClass, "Entity classes cannot contain null.");
            if (!isOwnedOrSharedClass(identity.classLoader(), entityClass.getClassLoader())) {
                throw new SecurityException("ORM entity classes must belong to the bound plugin or a parent class loader.");
            }
        }
        return validated;
    }

    private static boolean isOwnedOrSharedClass(ClassLoader pluginLoader, ClassLoader entityLoader) {
        if (entityLoader == null) {
            return true;
        }
        for (ClassLoader current = pluginLoader; current != null; current = current.getParent()) {
            if (current == entityLoader) {
                return true;
            }
        }
        return false;
    }

    @Override
    public DatabaseProvider registerDatabaseOrThrow(DatabaseType databaseType, String connectionIdentifier) {
        PluginIdentity boundIdentity = requireIdentity();
        String pluginId = boundIdentity.pluginId();
        OwnerScope ownerScope = OwnerScope.of(pluginId);
        if (!DataProviderObservations.isEnabled(observer)) {
            return wrapProvider(
                    handler,
                    boundIdentity,
                    handler.registerDatabaseOrThrow(boundIdentity, databaseType, connectionIdentifier),
                    observer,
                    pluginId,
                    ownerScope,
                    databaseType
            );
        }
        return DataProviderObservations.observe(
                observer,
                operationContext(pluginId, ownerScope, databaseType, "database.register"),
                () -> wrapProvider(
                        handler,
                        boundIdentity,
                        handler.registerDatabaseOrThrow(boundIdentity, databaseType, connectionIdentifier),
                        observer,
                        pluginId,
                        ownerScope,
                        databaseType
                )
        );
    }

    @Override
    public DataProviderScope scope(OwnerScope ownerScope) {
        PluginIdentity boundIdentity = requireIdentity();
        handler.requireIdentity(boundIdentity);
        return new DefaultDataProviderScope(
                handler,
                ownerScope,
                boundIdentity,
                observer,
                boundIdentity.pluginId()
        );
    }

    @Override
    public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        PluginIdentity boundIdentity = requireIdentity();
        if (!DataProviderObservations.isEnabled(observer)) {
            handler.unregisterDatabase(boundIdentity, databaseType, connectionIdentifier);
            return;
        }
        String pluginId = boundIdentity.pluginId();
        DataProviderObservations.observe(
                observer,
                operationContext(pluginId, OwnerScope.of(pluginId), databaseType, "database.unregister"),
                () -> handler.unregisterDatabase(boundIdentity, databaseType, connectionIdentifier)
        );
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
        String pluginId = boundIdentity.pluginId();
        OwnerScope ownerScope = OwnerScope.of(pluginId);
        return wrapProvider(
                handler,
                boundIdentity,
                handler.requireRegisteredDatabase(boundIdentity, databaseType, connectionIdentifier),
                observer,
                pluginId,
                ownerScope,
                databaseType
        );
    }

    static DatabaseProvider wrapProvider(
            DataProviderHandler handler,
            PluginIdentity identity,
            DatabaseProvider provider
    ) {
        return IdentityBoundDatabaseProvider.wrap(handler, identity, provider);
    }

    static DatabaseProvider wrapProvider(
            DataProviderHandler handler,
            PluginIdentity identity,
            DatabaseProvider provider,
            DataProviderObserver observer,
            String pluginId,
            OwnerScope ownerScope,
            DatabaseType databaseType
    ) {
        return IdentityBoundDatabaseProvider.wrap(
                handler,
                identity,
                provider,
                observer,
                pluginId,
                ownerScope,
                databaseType
        );
    }

    private static DataProviderOperationContext operationContext(
            String pluginId,
            OwnerScope ownerScope,
            DatabaseType databaseType,
            String operation
    ) {
        return new DataProviderOperationContext(pluginId, ownerScope, databaseType, operation);
    }

    private PluginIdentity requireIdentity() {
        if (identity == null) {
            throw new IllegalStateException("Bind DataProviderAPI with forPlugin(plugin) before use.");
        }
        return identity;
    }
}
