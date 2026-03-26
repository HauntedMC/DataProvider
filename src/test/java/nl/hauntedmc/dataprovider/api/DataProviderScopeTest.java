package nl.hauntedmc.dataprovider.api;

import nl.hauntedmc.dataprovider.database.DataAccess;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataProviderScopeTest {

    @Test
    void validatesOwnerScopeInput() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderAPI api = new DataProviderAPI(handler);

        assertThrows(IllegalArgumentException.class, () -> api.scope(null));
        assertThrows(IllegalArgumentException.class, () -> api.scope(" "));
        assertThrows(IllegalArgumentException.class, () -> api.scope("bad scope"));
    }

    @Test
    void typedScopeMethodsDelegateAndWrapResults() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        StubDatabaseProvider provider = new StubDatabaseProvider(new StubDataAccess());
        when(handler.registerDatabaseForScope("component.scope", DatabaseType.MYSQL, "default")).thenReturn(provider);

        DataProviderScope scope = new DataProviderAPI(handler).scope("component.scope");

        Optional<DatabaseProvider> optional = scope.registerDatabaseOptional(DatabaseType.MYSQL, "default");
        Optional<StubDatabaseProvider> typedProvider = scope.registerDatabaseAs(
                DatabaseType.MYSQL,
                "default",
                StubDatabaseProvider.class
        );
        Optional<StubDataAccess> typedDataAccess = scope.registerDataAccess(
                DatabaseType.MYSQL,
                "default",
                StubDataAccess.class
        );

        assertTrue(optional.isPresent());
        assertFalse(typedProvider.isPresent());
        assertTrue(typedDataAccess.isPresent());
    }

    @Test
    void unregisterAndCloseDelegateToScopedLifecycleMethods() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderScope scope = new DataProviderAPI(handler).scope("component.scope");

        scope.unregisterDatabase(DatabaseType.MYSQL, "default");
        scope.unregisterAllDatabases();
        scope.close();

        verify(handler).unregisterDatabaseForScope("component.scope", DatabaseType.MYSQL, "default");
        verify(handler, times(2)).unregisterAllDatabasesForScope("component.scope");
    }

    @Test
    void exposesNormalizedOwnerScope() {
        DataProviderScope scope = new DataProviderAPI(mock(DataProviderHandler.class)).scope(" component.scope ");
        assertEquals("component.scope", scope.ownerScope());
    }

    private static final class StubDataAccess implements DataAccess {
    }

    private static final class StubDatabaseProvider implements DatabaseProvider {
        private final DataAccess dataAccess;

        private StubDatabaseProvider(DataAccess dataAccess) {
            this.dataAccess = dataAccess;
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public DataAccess getDataAccess() {
            return dataAccess;
        }

        @Override
        public DataSource getDataSource() {
            return null;
        }
    }
}
