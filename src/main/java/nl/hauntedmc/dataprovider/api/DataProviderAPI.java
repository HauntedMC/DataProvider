package nl.hauntedmc.dataprovider.api;

import nl.hauntedmc.dataprovider.database.DataAccess;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.Optional;

/**
 * DataProviderAPI is the public facade that exposes safe, read-only database handles
 * for third-party plugins. Internally, it delegates to a DataProviderHandler, but it does not
 * expose lifecycle-sensitive methods (like shutdownAllDatabases or getActiveDatabases).
 *
 * For most integrations, the primary lifecycle is:
 * register -> use provider/data access -> unregister.
 * Optional scoped ownership is available through {@link #scope(String)} for advanced cases
 * where one plugin/software process needs isolated ownership domains for independently
 * managed components.
 */
public class DataProviderAPI {

    private final DataProviderHandler handler;

    /**
     * Constructs the API wrapper.
     *
     * @param handler the internal DataProviderHandler instance.
     */
    public DataProviderAPI(DataProviderHandler handler) {
        this.handler = Objects.requireNonNull(handler, "DataProviderHandler cannot be null");
    }

    /**
     * Registers a database connection for the resolved caller plugin.
     * This is the default path for most integrations.
     *
     * @param databaseType         the type of database (e.g. MYSQL, MONGODB, etc.)
     * @param connectionIdentifier a unique identifier for the connection
     * @return the registered read-only {@link DatabaseProvider} handle.
     */
    public DatabaseProvider registerDatabase(DatabaseType databaseType, String connectionIdentifier) {
        return wrapProvider(handler.registerDatabase(databaseType, connectionIdentifier));
    }

    /**
     * Creates an optional scoped lifecycle facade.
     * Default integrations usually do not need this.
     */
    public DataProviderScope scope(String ownerScope) {
        return scope(OwnerScope.of(ownerScope));
    }

    /**
     * Creates an optional scoped lifecycle facade using a typed owner scope.
     */
    public DataProviderScope scope(OwnerScope ownerScope) {
        return new DataProviderScope(handler, ownerScope);
    }

    /**
     * Registers a database connection and returns the result as Optional.
     */
    public Optional<DatabaseProvider> registerDatabaseOptional(DatabaseType databaseType, String connectionIdentifier) {
        return Optional.ofNullable(registerDatabase(databaseType, connectionIdentifier));
    }

    /**
     * Registers a database connection and casts it to an expected provider subtype.
     */
    public <T extends DatabaseProvider> Optional<T> registerDatabaseAs(
            DatabaseType databaseType,
            String connectionIdentifier,
            Class<T> expectedProviderType
    ) {
        return castProvider(registerDatabase(databaseType, connectionIdentifier), expectedProviderType);
    }

    /**
     * Registers a database connection and returns a typed data access view.
     */
    public <T extends DataAccess> Optional<T> registerDataAccess(
            DatabaseType databaseType,
            String connectionIdentifier,
            Class<T> expectedDataAccessType
    ) {
        Objects.requireNonNull(expectedDataAccessType, "Expected data access type cannot be null.");
        return registerDatabaseOptional(databaseType, connectionIdentifier)
                .flatMap(provider -> provider.getDataAccessOptional(expectedDataAccessType));
    }

    /**
     * Unregisters a specific database connection for the resolved caller plugin.
     * This is the default path for most integrations.
     *
     * @param databaseType         the type of database.
     * @param connectionIdentifier the connection identifier.
     */
    public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        handler.unregisterDatabase(databaseType, connectionIdentifier);
    }

    /**
     * Unregisters all database connections for the resolved caller plugin default owner scope.
     */
    public void unregisterAllDatabases() {
        handler.unregisterAllDatabases();
    }

    /**
     * Unregisters all database connections for the caller plugin across all caller scopes.
     * Use this for deterministic full-plugin shutdown cleanup.
     */
    public void unregisterAllDatabasesForPlugin() {
        handler.unregisterAllDatabasesForPlugin();
    }

    /**
     * Retrieves a registered database connection for the resolved caller plugin.
     *
     * @param databaseType         the type of database.
     * @param connectionIdentifier the connection identifier.
     * @return the {@link DatabaseProvider} instance, or null if not registered.
     */
    public DatabaseProvider getRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        return wrapProvider(handler.getRegisteredDatabase(databaseType, connectionIdentifier));
    }

    /**
     * Retrieves a registered database connection as Optional.
     */
    public Optional<DatabaseProvider> getRegisteredDatabaseOptional(DatabaseType databaseType, String connectionIdentifier) {
        return Optional.ofNullable(getRegisteredDatabase(databaseType, connectionIdentifier));
    }

    /**
     * Retrieves a registered database connection cast to an expected provider subtype.
     */
    public <T extends DatabaseProvider> Optional<T> getRegisteredDatabaseAs(
            DatabaseType databaseType,
            String connectionIdentifier,
            Class<T> expectedProviderType
    ) {
        return castProvider(getRegisteredDatabase(databaseType, connectionIdentifier), expectedProviderType);
    }

    /**
     * Retrieves a typed data access view from a registered database connection.
     */
    public <T extends DataAccess> Optional<T> getRegisteredDataAccess(
            DatabaseType databaseType,
            String connectionIdentifier,
            Class<T> expectedDataAccessType
    ) {
        Objects.requireNonNull(expectedDataAccessType, "Expected data access type cannot be null.");
        return getRegisteredDatabaseOptional(databaseType, connectionIdentifier)
                .flatMap(provider -> provider.getDataAccessOptional(expectedDataAccessType));
    }

    static <T extends DatabaseProvider> Optional<T> castProvider(
            DatabaseProvider provider,
            Class<T> expectedProviderType
    ) {
        Objects.requireNonNull(expectedProviderType, "Expected provider type cannot be null.");
        if (provider == null || !expectedProviderType.isInstance(provider)) {
            return Optional.empty();
        }
        return Optional.of(expectedProviderType.cast(provider));
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

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public DataAccess getDataAccess() {
            return delegate.getDataAccess();
        }

        @Override
        public DataSource getDataSource() {
            return delegate.getDataSource();
        }
    }

    private record RelationalDatabaseProviderView(RelationalDatabaseProvider delegate)
            implements RelationalDatabaseProvider, WrappedDatabaseProvider {
        private RelationalDatabaseProviderView {
            Objects.requireNonNull(delegate, "Delegate relational database provider cannot be null.");
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public RelationalDataAccess getDataAccess() {
            return delegate.getDataAccess();
        }

        @Override
        public DataSource getDataSource() {
            return delegate.getDataSource();
        }

        @Override
        public SchemaManager getSchemaManager() {
            return delegate.getSchemaManager();
        }
    }

    private record DocumentDatabaseProviderView(DocumentDatabaseProvider delegate)
            implements DocumentDatabaseProvider, WrappedDatabaseProvider {
        private DocumentDatabaseProviderView {
            Objects.requireNonNull(delegate, "Delegate document database provider cannot be null.");
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public DocumentDataAccess getDataAccess() {
            return delegate.getDataAccess();
        }
    }

    private record KeyValueDatabaseProviderView(KeyValueDatabaseProvider delegate)
            implements KeyValueDatabaseProvider, WrappedDatabaseProvider {
        private KeyValueDatabaseProviderView {
            Objects.requireNonNull(delegate, "Delegate key-value database provider cannot be null.");
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public KeyValueDataAccess getDataAccess() {
            return delegate.getDataAccess();
        }
    }

    private record MessagingDatabaseProviderView(MessagingDatabaseProvider delegate)
            implements MessagingDatabaseProvider, WrappedDatabaseProvider {
        private MessagingDatabaseProviderView {
            Objects.requireNonNull(delegate, "Delegate messaging database provider cannot be null.");
        }

        @Override
        public boolean isConnected() {
            return delegate.isConnected();
        }

        @Override
        public MessagingDataAccess getDataAccess() {
            return delegate.getDataAccess();
        }
    }
}
