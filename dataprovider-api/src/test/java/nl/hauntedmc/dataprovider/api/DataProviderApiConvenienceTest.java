package nl.hauntedmc.dataprovider.api;

import nl.hauntedmc.dataprovider.api.orm.ORMContext;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataProviderApiConvenienceTest {

    private static final ORMContext ORM_CONTEXT = new ORMContext() {
        @Override
        public <T> T runInTransaction(TransactionCallback<T> callback) {
            throw new UnsupportedOperationException("Not used by this contract test.");
        }

        @Override
        public void shutdown() {
        }
    };

    private static final RelationalDatabaseProvider RELATIONAL_PROVIDER = new RelationalDatabaseProvider() {
        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public SchemaManager getSchemaManager() {
            return null;
        }

        @Override
        public RelationalDataAccess getDataAccess() {
            return null;
        }

        @Override
        public DataSource getDataSource() {
            return null;
        }
    };

    @Test
    void typedApiRegistrationAndLookupReturnRequestedProviderType() {
        StubApi api = new StubApi(RELATIONAL_PROVIDER);

        assertSame(RELATIONAL_PROVIDER, api.registerDatabaseOrThrow(
                DatabaseType.MYSQL, "default", RelationalDatabaseProvider.class
        ));
        assertSame(RELATIONAL_PROVIDER, api.requireRegisteredDatabase(
                DatabaseType.MYSQL, "default", RelationalDatabaseProvider.class
        ));
        assertThrows(ClassCastException.class, () -> api.registerDatabaseOrThrow(
                DatabaseType.MYSQL, "default", DocumentDatabaseProvider.class
        ));
    }

    @Test
    void typedScopeRegistrationAndLookupReturnRequestedProviderType() {
        StubScope scope = new StubScope(RELATIONAL_PROVIDER);

        assertSame(RELATIONAL_PROVIDER, scope.registerDatabaseOrThrow(
                DatabaseType.MYSQL, "default", RelationalDatabaseProvider.class
        ));
        assertSame(RELATIONAL_PROVIDER, scope.requireRegisteredDatabase(
                DatabaseType.MYSQL, "default", RelationalDatabaseProvider.class
        ));
    }

    @Test
    void scopeLifecyclePredicatesReflectLifecycleState() {
        StubScope open = new StubScope(RELATIONAL_PROVIDER, DataProviderScope.LifecycleState.OPEN);
        StubScope closing = new StubScope(RELATIONAL_PROVIDER, DataProviderScope.LifecycleState.CLOSING);
        StubScope closed = new StubScope(RELATIONAL_PROVIDER, DataProviderScope.LifecycleState.CLOSED);

        assertTrue(open.isOpen());
        assertFalse(open.isClosing());
        assertFalse(open.isClosed());
        assertTrue(closing.isClosing());
        assertFalse(closing.isOpen());
        assertTrue(closed.isClosed());
        assertFalse(closed.isOpen());
    }

    @Test
    void apiSupplierCanBindFacadeInOneCall() {
        StubApi api = new StubApi(RELATIONAL_PROVIDER);
        DataProviderApiSupplier supplier = () -> api;
        Object platformPlugin = new Object();

        assertSame(api, supplier.dataProviderApiFor(platformPlugin));
        assertSame(platformPlugin, api.boundPlugin);
    }

    @Test
    void configuredOrmConvenienceMethodDelegatesThroughLegacySignature() {
        StubApi api = new StubApi(RELATIONAL_PROVIDER);

        assertSame(ORM_CONTEXT, api.createConfiguredOrmContext(null, null, String.class, Integer.class));
        assertEquals("validate", api.schemaMode);
        assertArrayEquals(new Class<?>[] {String.class, Integer.class}, api.entityClasses);
    }

    private static final class StubApi implements DataProviderAPI {
        private final DatabaseProvider provider;
        private Object boundPlugin;
        private String schemaMode;
        private Class<?>[] entityClasses;

        private StubApi(DatabaseProvider provider) {
            this.provider = provider;
        }

        @Override
        public DataProviderAPI forPlugin(Object platformPlugin) {
            boundPlugin = platformPlugin;
            return this;
        }

        @Override
        public ORMContext createOrmContext(
                DataSource dataSource,
                LoggerAdapter logger,
                String schemaMode,
                Class<?>... entityClasses
        ) {
            this.schemaMode = schemaMode;
            this.entityClasses = entityClasses;
            return ORM_CONTEXT;
        }

        @Override
        public DatabaseProvider registerDatabaseOrThrow(DatabaseType databaseType, String connectionIdentifier) {
            return provider;
        }

        @Override
        public DataProviderScope scope(OwnerScope ownerScope) {
            return new StubScope(provider);
        }

        @Override
        public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        }

        @Override
        public void unregisterAllDatabases() {
        }

        @Override
        public void unregisterAllDatabasesForPlugin() {
        }

        @Override
        public DatabaseProvider requireRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
            return provider;
        }
    }

    private static final class StubScope implements DataProviderScope {
        private final DatabaseProvider provider;
        private final LifecycleState state;

        private StubScope(DatabaseProvider provider) {
            this(provider, LifecycleState.OPEN);
        }

        private StubScope(DatabaseProvider provider, LifecycleState state) {
            this.provider = provider;
            this.state = state;
        }

        @Override
        public OwnerScope ownerScope() {
            return OwnerScope.of("test");
        }

        @Override
        public LifecycleState lifecycleState() {
            return state;
        }

        @Override
        public DatabaseProvider registerDatabaseOrThrow(DatabaseType databaseType, String connectionIdentifier) {
            return provider;
        }

        @Override
        public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        }

        @Override
        public void unregisterAllDatabases() {
        }

        @Override
        public DatabaseProvider requireRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
            return provider;
        }
    }
}
