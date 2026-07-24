package nl.hauntedmc.dataprovider.core;

import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.core.identity.CallerContext;
import nl.hauntedmc.dataprovider.core.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.exception.DataProviderRegistrationException;
import nl.hauntedmc.dataprovider.exception.ProviderClosedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StrictDataProviderHandlerTest {

    @Test
    void strictOperationsUseTheResolvedPluginAndScope() {
        DataProviderRegistry registry = mock(DataProviderRegistry.class);
        DataProviderHandler handler = handler(registry);
        DatabaseProvider provider = mock(DatabaseProvider.class);
        PluginId plugin = PluginId.of("feature-plugin");
        ConnectionIdentifier identifier = ConnectionIdentifier.of("default");
        OwnerScope scope = OwnerScope.of("component.scope");

        when(registry.registerDatabase(plugin, OwnerScopeId.of(plugin.value()), DatabaseType.MYSQL, identifier))
                .thenReturn(provider);
        when(registry.getDatabase(plugin, OwnerScopeId.from(scope), DatabaseType.MYSQL, identifier))
                .thenReturn(provider);

        assertSame(provider, handler.registerDatabaseOrThrow(DatabaseType.MYSQL, "default"));
        assertSame(provider, handler.requireRegisteredDatabaseForScope(scope, DatabaseType.MYSQL, "default"));

        verify(registry).registerDatabase(plugin, OwnerScopeId.of(plugin.value()), DatabaseType.MYSQL, identifier);
        verify(registry).getDatabase(plugin, OwnerScopeId.from(scope), DatabaseType.MYSQL, identifier);
    }

    @Test
    void strictLookupReportsMissingRegistration() {
        DataProviderRegistry registry = mock(DataProviderRegistry.class);
        DataProviderHandler handler = handler(registry);

        assertThrows(DataProviderRegistrationException.class,
                () -> handler.requireRegisteredDatabase(DatabaseType.MYSQL, "default"));
    }

    @Test
    void everyPublicOperationUsesStructuredClosedProviderFailures() {
        DataProviderRegistry registry = mock(DataProviderRegistry.class);
        when(registry.isClosed()).thenReturn(true);
        DataProviderHandler handler = handler(registry);

        assertThrows(ProviderClosedException.class,
                () -> handler.registerDatabaseOrThrow(DatabaseType.MYSQL, "default"));
        assertThrows(ProviderClosedException.class,
                () -> handler.requireRegisteredDatabase(DatabaseType.MYSQL, "default"));
        assertThrows(ProviderClosedException.class,
                () -> handler.unregisterDatabase(DatabaseType.MYSQL, "default"));
        assertThrows(ProviderClosedException.class, handler::unregisterAllDatabases);
        assertThrows(ProviderClosedException.class, handler::unregisterAllDatabasesForPlugin);
        assertThrows(ProviderClosedException.class, handler::getCurrentPluginId);
    }

    private DataProviderHandler handler(DataProviderRegistry registry) {
        ClassLoader pluginLoader = new ClassLoader() { };
        CallerContextResolver resolver = () -> new CallerContext("feature-plugin", pluginLoader);
        return new DataProviderHandler(registry, resolver, new RecordingLoggerAdapter(), getClass().getClassLoader());
    }
}
