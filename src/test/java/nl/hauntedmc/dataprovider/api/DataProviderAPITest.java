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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataProviderAPITest {

    @Test
    void constructorRejectsNullHandler() {
        assertThrows(NullPointerException.class, () -> new DataProviderAPI(null));
    }

    @Test
    void registerAndLookupOptionalApisHandleNullProvider() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        when(handler.registerDatabase(DatabaseType.MYSQL, "default")).thenReturn(null);
        when(handler.getRegisteredDatabase(DatabaseType.MYSQL, "default")).thenReturn(null);

        DataProviderAPI api = new DataProviderAPI(handler);

        assertEquals(Optional.empty(), api.registerDatabaseOptional(DatabaseType.MYSQL, "default"));
        assertEquals(Optional.empty(), api.getRegisteredDatabaseOptional(DatabaseType.MYSQL, "default"));
    }

    @Test
    void providerCastingAndDataAccessViewsReturnExpectedOptionalResults() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        StubDatabaseProvider provider = new StubDatabaseProvider(new StubDataAccess());
        when(handler.registerDatabase(DatabaseType.REDIS, "cache")).thenReturn(provider);
        when(handler.getRegisteredDatabase(DatabaseType.REDIS, "cache")).thenReturn(provider);

        DataProviderAPI api = new DataProviderAPI(handler);

        Optional<StubDatabaseProvider> registerAs = api.registerDatabaseAs(
                DatabaseType.REDIS,
                "cache",
                StubDatabaseProvider.class
        );
        Optional<StubDatabaseProvider> lookupAs = api.getRegisteredDatabaseAs(
                DatabaseType.REDIS,
                "cache",
                StubDatabaseProvider.class
        );
        Optional<StubDataAccess> registerAccess = api.registerDataAccess(
                DatabaseType.REDIS,
                "cache",
                StubDataAccess.class
        );
        Optional<StubDataAccess> lookupAccess = api.getRegisteredDataAccess(
                DatabaseType.REDIS,
                "cache",
                StubDataAccess.class
        );

        assertTrue(registerAs.isPresent());
        assertTrue(lookupAs.isPresent());
        assertTrue(registerAccess.isPresent());
        assertTrue(lookupAccess.isPresent());
    }

    @Test
    void providerCastingAndDataAccessViewsReturnEmptyWhenTypeMismatches() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        StubDatabaseProvider provider = new StubDatabaseProvider(new StubDataAccess());
        when(handler.registerDatabase(DatabaseType.REDIS, "cache")).thenReturn(provider);
        when(handler.getRegisteredDatabase(DatabaseType.REDIS, "cache")).thenReturn(provider);

        DataProviderAPI api = new DataProviderAPI(handler);

        Optional<OtherDatabaseProvider> providerView = api.registerDatabaseAs(
                DatabaseType.REDIS,
                "cache",
                OtherDatabaseProvider.class
        );
        Optional<OtherDataAccess> dataAccessView = api.getRegisteredDataAccess(
                DatabaseType.REDIS,
                "cache",
                OtherDataAccess.class
        );

        assertFalse(providerView.isPresent());
        assertFalse(dataAccessView.isPresent());
    }

    @Test
    void unregisterOperationsDelegateToHandler() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderAPI api = new DataProviderAPI(handler);

        api.unregisterDatabase(DatabaseType.MYSQL, "default");
        api.unregisterAllDatabases();

        verify(handler).unregisterDatabase(DatabaseType.MYSQL, "default");
        verify(handler).unregisterAllDatabases();
    }

    @Test
    void typedMethodsRejectNullExpectedTypes() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderAPI api = new DataProviderAPI(handler);

        assertThrows(NullPointerException.class, () ->
                api.registerDatabaseAs(DatabaseType.MYSQL, "default", null));
        assertThrows(NullPointerException.class, () ->
                api.getRegisteredDatabaseAs(DatabaseType.MYSQL, "default", null));
        assertThrows(NullPointerException.class, () ->
                api.registerDataAccess(DatabaseType.MYSQL, "default", null));
        assertThrows(NullPointerException.class, () ->
                api.getRegisteredDataAccess(DatabaseType.MYSQL, "default", null));
    }

    private static final class StubDataAccess implements DataAccess {
    }

    private static final class OtherDataAccess implements DataAccess {
    }

    private static class StubDatabaseProvider implements DatabaseProvider {
        private final DataAccess dataAccess;

        private StubDatabaseProvider(DataAccess dataAccess) {
            this.dataAccess = dataAccess;
        }

        @Override
        public void connect() {
        }

        @Override
        public void disconnect() {
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

    private static final class OtherDatabaseProvider extends StubDatabaseProvider {
        private OtherDatabaseProvider() {
            super(new OtherDataAccess());
        }
    }
}
