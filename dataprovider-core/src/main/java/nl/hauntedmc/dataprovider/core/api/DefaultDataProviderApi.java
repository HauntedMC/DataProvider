package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.api.DataProviderScope;
import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.api.orm.ORMContext;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionDataSource;
import nl.hauntedmc.dataprovider.database.DataAccess;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;

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
            String pluginName,
            DataSource dataSource,
            nl.hauntedmc.dataprovider.logging.LoggerAdapter logger,
            String schemaMode,
            Class<?>... entityClasses
    ) {
        if (!(Objects.requireNonNull(dataSource, "DataSource cannot be null") instanceof ExecutionDataSource)) {
            throw new IllegalArgumentException(
                    "ORMContext requires the scoped DataSource returned by a registered relational provider."
            );
        }
        return new nl.hauntedmc.dataprovider.core.orm.ORMContext(
                pluginName, dataSource, logger, schemaMode, entityClasses);
    }

    @Override
    public DatabaseProvider registerDatabase(DatabaseType databaseType, String connectionIdentifier) {
        return wrapProvider(handler.registerDatabase(databaseType, connectionIdentifier));
    }

    @Override
    public DatabaseProvider registerDatabaseOrThrow(DatabaseType databaseType, String connectionIdentifier) {
        return wrapProvider(handler.registerDatabaseOrThrow(databaseType, connectionIdentifier));
    }

    @Override
    public DataProviderScope scope(OwnerScope ownerScope) {
        return new DefaultDataProviderScope(handler, ownerScope);
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
    public DatabaseProvider getRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        return wrapProvider(handler.getRegisteredDatabase(databaseType, connectionIdentifier));
    }

    @Override
    public DatabaseProvider requireRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        return wrapProvider(handler.requireRegisteredDatabase(databaseType, connectionIdentifier));
    }

    static DatabaseProvider wrapProvider(DatabaseProvider provider) {
        if (provider == null || provider instanceof WrappedDatabaseProvider) {
            return provider;
        }
        if (provider instanceof RelationalDatabaseProvider relationalProvider) {
            return new RelationalDatabaseProviderView(relationalProvider);
        }
        if (provider instanceof DocumentDatabaseProvider documentProvider) {
            return new DocumentDatabaseProviderView(documentProvider);
        }
        if (provider instanceof KeyValueDatabaseProvider keyValueProvider) {
            return new KeyValueDatabaseProviderView(keyValueProvider);
        }
        if (provider instanceof MessagingDatabaseProvider messagingProvider) {
            return new MessagingDatabaseProviderView(messagingProvider);
        }
        return new DatabaseProviderView(provider);
    }

    private interface WrappedDatabaseProvider extends DatabaseProvider {
    }

    private record DatabaseProviderView(DatabaseProvider delegate) implements WrappedDatabaseProvider {
        private DatabaseProviderView {
            Objects.requireNonNull(delegate, "Delegate database provider cannot be null.");
        }
        @Override public boolean isConnected() { return delegate.isConnected(); }
        @Override public DataAccess getDataAccess() { return delegate.getDataAccess(); }
        @Override public DataSource getDataSource() { return delegate.getDataSource(); }
    }

    private record RelationalDatabaseProviderView(RelationalDatabaseProvider delegate)
            implements RelationalDatabaseProvider, WrappedDatabaseProvider {
        private RelationalDatabaseProviderView {
            Objects.requireNonNull(delegate, "Delegate relational database provider cannot be null.");
        }
        @Override public boolean isConnected() { return delegate.isConnected(); }
        @Override public RelationalDataAccess getDataAccess() { return delegate.getDataAccess(); }
        @Override public DataSource getDataSource() { return delegate.getDataSource(); }
        @Override public SchemaManager getSchemaManager() { return delegate.getSchemaManager(); }
    }

    private record DocumentDatabaseProviderView(DocumentDatabaseProvider delegate)
            implements DocumentDatabaseProvider, WrappedDatabaseProvider {
        private DocumentDatabaseProviderView {
            Objects.requireNonNull(delegate, "Delegate document database provider cannot be null.");
        }
        @Override public boolean isConnected() { return delegate.isConnected(); }
        @Override public DocumentDataAccess getDataAccess() { return delegate.getDataAccess(); }
    }

    private record KeyValueDatabaseProviderView(KeyValueDatabaseProvider delegate)
            implements KeyValueDatabaseProvider, WrappedDatabaseProvider {
        private KeyValueDatabaseProviderView {
            Objects.requireNonNull(delegate, "Delegate key-value database provider cannot be null.");
        }
        @Override public boolean isConnected() { return delegate.isConnected(); }
        @Override public KeyValueDataAccess getDataAccess() { return delegate.getDataAccess(); }
    }

    private record MessagingDatabaseProviderView(MessagingDatabaseProvider delegate)
            implements MessagingDatabaseProvider, WrappedDatabaseProvider {
        private MessagingDatabaseProviderView {
            Objects.requireNonNull(delegate, "Delegate messaging database provider cannot be null.");
        }
        @Override public boolean isConnected() { return delegate.isConnected(); }
        @Override public MessagingDataAccess getDataAccess() { return delegate.getDataAccess(); }
    }
}
