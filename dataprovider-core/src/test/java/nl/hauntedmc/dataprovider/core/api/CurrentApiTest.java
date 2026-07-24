package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.api.DataProviderScope;
import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.core.concurrent.ScopedDataSource;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.exception.ProviderClosedException;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CurrentApiTest {

    @Test
    void strictRegistrationAndLookupDelegateToTheHandler() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DatabaseProvider provider = mock(DatabaseProvider.class);
        when(handler.registerDatabaseOrThrow(DatabaseType.MYSQL, "default")).thenReturn(provider);
        when(handler.requireRegisteredDatabase(DatabaseType.MYSQL, "default")).thenReturn(provider);
        DataProviderAPI api = new DefaultDataProviderApi(handler);

        api.registerDatabaseOrThrow(DatabaseType.MYSQL, "default");
        api.requireRegisteredDatabase(DatabaseType.MYSQL, "default");
        verify(handler).registerDatabaseOrThrow(DatabaseType.MYSQL, "default");
        verify(handler).requireRegisteredDatabase(DatabaseType.MYSQL, "default");
    }

    @Test
    void scopedStrictRegistrationDelegatesToTheHandler() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DatabaseProvider provider = mock(DatabaseProvider.class);
        OwnerScope ownerScope = OwnerScope.of("component.scope");
        when(handler.registerDatabaseForScopeOrThrow(ownerScope, DatabaseType.REDIS, "cache")).thenReturn(provider);
        DataProviderScope scope = new DefaultDataProviderApi(handler).scope(ownerScope);

        scope.registerDatabaseOrThrow(DatabaseType.REDIS, "cache");
        verify(handler).registerDatabaseForScopeOrThrow(ownerScope, DatabaseType.REDIS, "cache");
    }

    @Test
    void strictRegistrationPreservesTheBackendSpecificProviderContract() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        RelationalDatabaseProvider provider = mock(RelationalDatabaseProvider.class);
        RelationalDataAccess dataAccess = mock(RelationalDataAccess.class);
        when(provider.getDataAccess()).thenReturn(dataAccess);
        when(handler.registerDatabaseOrThrow(DatabaseType.MYSQL, "default")).thenReturn(provider);

        DatabaseProvider result = new DefaultDataProviderApi(handler)
                .registerDatabaseOrThrow(DatabaseType.MYSQL, "default");

        assertTrue(result instanceof RelationalDatabaseProvider);
        assertTrue(((RelationalDatabaseProvider) result).getDataAccess() == dataAccess);
    }

    @Test
    void closedScopeRejectsAllPublicOperationsWithStructuredFailure() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderScope scope = new DefaultDataProviderApi(handler).scope("component.scope");
        scope.close();

        assertThrows(ProviderClosedException.class,
                () -> scope.registerDatabaseOrThrow(DatabaseType.MYSQL, "default"));
        assertThrows(ProviderClosedException.class,
                () -> scope.requireRegisteredDatabase(DatabaseType.MYSQL, "default"));
        assertThrows(ProviderClosedException.class,
                () -> scope.unregisterDatabase(DatabaseType.MYSQL, "default"));
        assertThrows(ProviderClosedException.class, scope::unregisterAllDatabases);
    }

    @Test
    void ormRequiresAManagedDataSourceAndUsesResolvedIdentity() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        when(handler.getCurrentPluginId()).thenReturn("resolved-plugin");
        DataProviderAPI api = new DefaultDataProviderApi(handler);

        assertThrows(IllegalArgumentException.class, () -> api.createOrmContext(
                mock(DataSource.class), mock(nl.hauntedmc.dataprovider.logging.LoggerAdapter.class), "none"
        ));
        assertThrows(IllegalArgumentException.class, () -> api.createOrmContext(
                mock(ScopedDataSource.class), mock(nl.hauntedmc.dataprovider.logging.LoggerAdapter.class), "none"
        ));
        assertTrue(DefaultDataProviderApi.isManagedDataSource(mock(ScopedDataSource.class)));
        verify(handler).getCurrentPluginId();
    }
}
