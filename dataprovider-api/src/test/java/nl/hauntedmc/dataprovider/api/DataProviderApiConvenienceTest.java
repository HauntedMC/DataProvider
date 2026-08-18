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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void configuredOrmConvenienceMethodDelegatesThroughLegacySignature() {
        StubApi api = new StubApi(RELATIONAL_PROVIDER);

        assertSame(ORM_CONTEXT, api.createConfiguredOrmContext(null, null, String.class, Integer.class));
        assertEquals("validate", api.schemaMode);
        assertArrayEquals(new Class<?>[] {String.class, Integer.class}, api.entityClasses);
    }

    private static final class StubApi implements DataProviderAPI {
        private final DatabaseProvider provider;
        private String schemaMode;
        private Class<?>[] entityClasses;

        private StubApi(DatabaseProvider provider) {
            this.provider = provider;
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

        private StubScope(DatabaseProvider provider) {
            this.provider = provider;
        }

        @Override
        public OwnerScope ownerScope() {
            return OwnerScope.of("test");
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
