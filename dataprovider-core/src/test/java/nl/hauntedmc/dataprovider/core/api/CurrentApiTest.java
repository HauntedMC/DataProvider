package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.api.DataProviderScope;
import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.core.concurrent.ScopedDataSource;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityRegistry;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.exception.ProviderClosedException;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class CurrentApiTest {

    @Test
    void strictRegistrationAndLookupDelegateToTheHandler() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        PluginIdentity identity = identity("plugin");
        when(handler.getPluginId(identity)).thenReturn("plugin");
        DatabaseProvider provider = mock(DatabaseProvider.class);
        when(handler.registerDatabaseOrThrow(identity, DatabaseType.MYSQL, "default")).thenReturn(provider);
        when(handler.requireRegisteredDatabase(identity, DatabaseType.MYSQL, "default")).thenReturn(provider);
        DataProviderAPI api = boundApi(handler, identity);

        api.registerDatabaseOrThrow(DatabaseType.MYSQL, "default");
        api.requireRegisteredDatabase(DatabaseType.MYSQL, "default");
        verify(handler).registerDatabaseOrThrow(identity, DatabaseType.MYSQL, "default");
        verify(handler).requireRegisteredDatabase(identity, DatabaseType.MYSQL, "default");
    }

    @Test
    void scopedStrictRegistrationDelegatesToTheHandler() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        PluginIdentity identity = identity("plugin");
        when(handler.getPluginId(identity)).thenReturn("plugin");
        DatabaseProvider provider = mock(DatabaseProvider.class);
        OwnerScope ownerScope = OwnerScope.of("component.scope");
        when(handler.registerDatabaseForScopeOrThrow(identity, ownerScope, DatabaseType.REDIS, "cache")).thenReturn(provider);
        DataProviderScope scope = boundApi(handler, identity).scope(ownerScope);

        scope.registerDatabaseOrThrow(DatabaseType.REDIS, "cache");
        verify(handler).registerDatabaseForScopeOrThrow(identity, ownerScope, DatabaseType.REDIS, "cache");
    }

    @Test
    void strictRegistrationPreservesTheBackendSpecificProviderContract() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        PluginIdentity identity = identity("plugin");
        when(handler.getPluginId(identity)).thenReturn("plugin");
        RelationalDatabaseProvider provider = mock(RelationalDatabaseProvider.class);
        RelationalDataAccess dataAccess = mock(RelationalDataAccess.class);
        when(provider.getDataAccess()).thenReturn(dataAccess);
        when(handler.registerDatabaseOrThrow(identity, DatabaseType.MYSQL, "default")).thenReturn(provider);

        DatabaseProvider result = boundApi(handler, identity)
                .registerDatabaseOrThrow(DatabaseType.MYSQL, "default");

        assertTrue(result instanceof RelationalDatabaseProvider);
        assertTrue(((RelationalDatabaseProvider) result).getDataAccess() instanceof RelationalDataAccess);
        verify(handler).requireIdentity(identity);
    }

    @Test
    void providerRejectsHandleAfterItsCapturedIdentityIsInvalidated() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        RelationalDatabaseProvider provider = mock(RelationalDatabaseProvider.class);
        PluginIdentity identity = identity("owner");
        AtomicBoolean active = new AtomicBoolean(true);
        when(handler.getPluginId(identity)).thenReturn("owner");
        doAnswer(invocation -> {
            if (!active.get()) {
                throw new SecurityException("This DataProvider handle belongs to a disabled or replaced plugin.");
            }
            return null;
        }).when(handler).requireIdentity(identity);
        when(handler.registerDatabaseOrThrow(identity, DatabaseType.MYSQL, "default")).thenReturn(provider);

        DatabaseProvider result = boundApi(handler, identity)
                .registerDatabaseOrThrow(DatabaseType.MYSQL, "default");

        assertDoesNotThrow(result::isConnected);
        active.set(false);
        assertThrows(SecurityException.class, result::isConnected);
    }

    @Test
    void boundHandlesUseTheCapturedIdentityInsteadOfCallerResolution() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        PluginIdentity identity = new PluginIdentityRegistry().register("owner", getClass().getClassLoader());
        RelationalDatabaseProvider provider = mock(RelationalDatabaseProvider.class);
        when(handler.issuePluginIdentity("plugin-instance")).thenReturn(identity);
        when(handler.getPluginId(identity)).thenReturn("owner");
        when(handler.registerDatabaseOrThrow(identity, DatabaseType.MYSQL, "default")).thenReturn(provider);

        DatabaseProvider result = new DefaultDataProviderApi(handler)
                .forPlugin("plugin-instance")
                .registerDatabaseOrThrow(DatabaseType.MYSQL, "default");

        result.isConnected();
        verify(handler).requireIdentity(identity);
        verify(handler, never()).requireCallerIdentity("owner");
    }

    @Test
    void boundHandleWorksFromCompletableFutureWithoutCallerResolution() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        PluginIdentity identity = identity("owner");
        RelationalDatabaseProvider provider = mock(RelationalDatabaseProvider.class);
        when(handler.getPluginId(identity)).thenReturn("owner");
        when(handler.registerDatabaseOrThrow(identity, DatabaseType.MYSQL, "default")).thenReturn(provider);
        DatabaseProvider result = boundApi(handler, identity).registerDatabaseOrThrow(DatabaseType.MYSQL, "default");

        CompletableFuture.runAsync(result::isConnected).join();

        verify(handler).requireIdentity(identity);
        verify(handler, never()).requireCallerIdentity(any());
    }

    @Test
    void dataSourceRejectsHandleAfterItsCapturedIdentityIsInvalidated() throws Exception {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        RelationalDatabaseProvider provider = mock(RelationalDatabaseProvider.class);
        DataSource dataSource = mock(DataSource.class);
        PluginIdentity identity = identity("owner");
        AtomicBoolean active = new AtomicBoolean(true);
        when(handler.getPluginId(identity)).thenReturn("owner");
        doAnswer(invocation -> {
            if (!active.get()) {
                throw new SecurityException("This DataProvider handle belongs to a disabled or replaced plugin.");
            }
            return null;
        }).when(handler).requireIdentity(identity);
        when(provider.getDataSource()).thenReturn(dataSource);
        when(handler.registerDatabaseOrThrow(identity, DatabaseType.MYSQL, "default")).thenReturn(provider);

        DataSource boundDataSource = ((RelationalDatabaseProvider) boundApi(handler, identity)
                .registerDatabaseOrThrow(DatabaseType.MYSQL, "default")).getDataSource();

        active.set(false);
        assertThrows(SecurityException.class, boundDataSource::getConnection);
    }

    @Test
    void closedScopeRejectsAllPublicOperationsWithStructuredFailure() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        PluginIdentity identity = identity("plugin");
        when(handler.getPluginId(identity)).thenReturn("plugin");
        DataProviderScope scope = boundApi(handler, identity).scope("component.scope");
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
    void ormRequiresAnIdentityBoundManagedDataSource() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        PluginIdentity identity = identity("resolved-plugin");
        when(handler.getPluginId(identity)).thenReturn("resolved-plugin");
        DataProviderAPI api = boundApi(handler, identity);

        assertThrows(IllegalArgumentException.class, () -> api.createOrmContext(
                mock(DataSource.class), mock(nl.hauntedmc.dataprovider.logging.LoggerAdapter.class), "none"
        ));
        assertThrows(IllegalArgumentException.class, () -> api.createOrmContext(
                mock(ScopedDataSource.class), mock(nl.hauntedmc.dataprovider.logging.LoggerAdapter.class), "none"
        ));
        assertFalse(DefaultDataProviderApi.isManagedDataSource(mock(ScopedDataSource.class)));
        RelationalDatabaseProvider provider = mock(RelationalDatabaseProvider.class);
        when(provider.getDataSource()).thenReturn(mock(ScopedDataSource.class));
        RelationalDatabaseProvider boundProvider = (RelationalDatabaseProvider)
                IdentityBoundDatabaseProvider.wrap(handler, identity, provider);
        assertTrue(DefaultDataProviderApi.isManagedDataSource(boundProvider.getDataSource()));
    }

    @Test
    void ormRejectsForeignOrNullEntityClasses() {
        PluginIdentity identity = identity("owner");
        ClassLoader foreignLoader = new ClassLoader() {
        };
        Class<?> foreignClass = Proxy.newProxyInstance(
                foreignLoader,
                new Class<?>[] {Runnable.class},
                (proxy, method, arguments) -> null
        ).getClass();

        assertThrows(SecurityException.class,
                () -> DefaultDataProviderApi.validateEntityClasses(identity, new Class<?>[] {foreignClass}));
        assertThrows(NullPointerException.class,
                () -> DefaultDataProviderApi.validateEntityClasses(identity, new Class<?>[] {null}));
        Class<?>[] input = {CurrentApiTest.class, String.class};
        Class<?>[] validated = DefaultDataProviderApi.validateEntityClasses(identity, input);
        assertNotSame(input, validated);
    }

    private PluginIdentity identity(String pluginId) {
        return new PluginIdentityRegistry().register(pluginId, getClass().getClassLoader());
    }

    private static DataProviderAPI boundApi(DataProviderHandler handler, PluginIdentity identity) {
        when(handler.issuePluginIdentity(any())).thenReturn(identity);
        return new DefaultDataProviderApi(handler).forPlugin(new Object());
    }
}
