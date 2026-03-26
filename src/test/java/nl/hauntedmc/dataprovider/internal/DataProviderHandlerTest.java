package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.internal.identity.CallerContext;
import nl.hauntedmc.dataprovider.internal.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.testutil.RecordingLoggerAdapter;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataProviderHandlerTest {

    @Test
    void injectedConstructorValidatesArguments() {
        DataProviderRegistry registry = mock(DataProviderRegistry.class);
        ClassLoader pluginLoader = new ClassLoader() {
        };
        CallerContextResolver resolver = () -> new CallerContext("plugin", pluginLoader);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        ClassLoader ownClassLoader = getClass().getClassLoader();

        assertThrows(NullPointerException.class, () -> new DataProviderHandler(null, resolver, logger, ownClassLoader));
        assertThrows(NullPointerException.class, () -> new DataProviderHandler(registry, null, logger, ownClassLoader));
        assertThrows(NullPointerException.class, () -> new DataProviderHandler(registry, resolver, null, ownClassLoader));
        assertThrows(NullPointerException.class, () -> new DataProviderHandler(registry, resolver, logger, null));
    }

    @Test
    void registerAndLookupDelegateUsingResolvedPluginContext() {
        DataProviderRegistry registry = mock(DataProviderRegistry.class);
        ClassLoader pluginLoader = new ClassLoader() {
        };
        CallerContextResolver resolver = () -> new CallerContext("feature-plugin", pluginLoader);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderHandler handler = new DataProviderHandler(registry, resolver, logger, getClass().getClassLoader());
        DatabaseProvider provider = mock(DatabaseProvider.class);

        when(registry.registerDatabase(
                PluginId.of("feature-plugin"),
                OwnerScopeId.of("feature-plugin"),
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("default")
        )).thenReturn(provider);
        when(registry.registerDatabase(
                PluginId.of("feature-plugin"),
                OwnerScopeId.of("component.scope"),
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("default")
        )).thenReturn(provider);
        when(registry.getDatabase(
                PluginId.of("feature-plugin"),
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("default")
        )).thenReturn(provider);

        assertSame(provider, handler.registerDatabase(DatabaseType.MYSQL, "default"));
        assertSame(provider, handler.registerDatabaseForScope("component.scope", DatabaseType.MYSQL, "default"));
        assertSame(provider, handler.getRegisteredDatabase(DatabaseType.MYSQL, "default"));
        handler.unregisterDatabase(DatabaseType.MYSQL, "default");
        handler.unregisterDatabaseForScope("component.scope", DatabaseType.MYSQL, "default");
        handler.unregisterAllDatabases();
        handler.unregisterAllDatabasesForScope("component.scope");
        handler.unregisterAllDatabasesForPlugin();

        verify(registry).registerDatabase(
                PluginId.of("feature-plugin"),
                OwnerScopeId.of("feature-plugin"),
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("default")
        );
        verify(registry).registerDatabase(
                PluginId.of("feature-plugin"),
                OwnerScopeId.of("component.scope"),
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("default")
        );
        verify(registry).getDatabase(
                PluginId.of("feature-plugin"),
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("default")
        );
        verify(registry).unregisterDatabase(
                PluginId.of("feature-plugin"),
                OwnerScopeId.of("feature-plugin"),
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("default")
        );
        verify(registry).unregisterDatabase(
                PluginId.of("feature-plugin"),
                OwnerScopeId.of("component.scope"),
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("default")
        );
        verify(registry).unregisterAllDatabases(PluginId.of("feature-plugin"), OwnerScopeId.of("feature-plugin"));
        verify(registry).unregisterAllDatabases(PluginId.of("feature-plugin"), OwnerScopeId.of("component.scope"));
        verify(registry).unregisterAllDatabasesForPlugin(PluginId.of("feature-plugin"));
    }

    @Test
    void validatesDatabaseTypeAndConnectionIdentifier() {
        DataProviderRegistry registry = mock(DataProviderRegistry.class);
        ClassLoader pluginLoader = new ClassLoader() {
        };
        CallerContextResolver resolver = () -> new CallerContext("plugin", pluginLoader);
        DataProviderHandler handler = new DataProviderHandler(registry, resolver, new RecordingLoggerAdapter(), getClass().getClassLoader());

        assertThrows(NullPointerException.class, () -> handler.registerDatabase(null, "default"));
        assertThrows(IllegalArgumentException.class, () -> handler.registerDatabase(DatabaseType.MYSQL, " "));
        assertThrows(IllegalArgumentException.class, () -> handler.registerDatabase(DatabaseType.MYSQL, "bad/identifier"));
        assertThrows(IllegalArgumentException.class, () -> handler.registerDatabaseForScope(" ", DatabaseType.MYSQL, "default"));
        assertThrows(
                IllegalArgumentException.class,
                () -> handler.registerDatabaseForScope("bad scope", DatabaseType.MYSQL, "default")
        );
        assertThrows(NullPointerException.class, () -> handler.getRegisteredDatabase(null, "default"));
        assertThrows(IllegalArgumentException.class, () -> handler.getRegisteredDatabase(DatabaseType.MYSQL, " "));
    }

    @Test
    void rejectsNullResolvedCallerContext() {
        DataProviderRegistry registry = mock(DataProviderRegistry.class);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderHandler nullCallerHandler = new DataProviderHandler(
                registry,
                () -> null,
                logger,
                getClass().getClassLoader()
        );

        assertThrows(SecurityException.class, () -> nullCallerHandler.unregisterAllDatabases());
        assertTrue(logger.errorMessages().stream().anyMatch(m -> m.contains("Could not resolve caller plugin context")));
    }

    @Test
    void propagatesResolverSecurityException() {
        DataProviderRegistry registry = mock(DataProviderRegistry.class);
        DataProviderHandler handler = new DataProviderHandler(
                registry,
                () -> {
                    throw new SecurityException("not a plugin");
                },
                new RecordingLoggerAdapter(),
                getClass().getClassLoader()
        );

        assertThrows(SecurityException.class, () -> handler.unregisterAllDatabases());
    }

    @Test
    void privilegedOperationsRejectNonInternalCallerClassLoader() {
        DataProviderRegistry registry = mock(DataProviderRegistry.class);
        ClassLoader pluginLoader = new ClassLoader() {
        };
        CallerContextResolver resolver = () -> new CallerContext("plugin", pluginLoader);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        ClassLoader mismatchingLoader = new ClassLoader() {
        };
        DataProviderHandler handler = new DataProviderHandler(registry, resolver, logger, mismatchingLoader);

        assertThrows(SecurityException.class, handler::shutdownAllDatabases);
        assertThrows(SecurityException.class, handler::getActiveDatabases);
        assertThrows(SecurityException.class, handler::getActiveDatabaseReferenceCounts);
    }

    @Test
    void privilegedOperationsReturnRegistrySnapshotsForInternalCaller() {
        DataProviderRegistry registry = mock(DataProviderRegistry.class);
        ClassLoader pluginLoader = new ClassLoader() {
        };
        CallerContextResolver resolver = () -> new CallerContext("plugin", pluginLoader);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderHandler handler = new DataProviderHandler(registry, resolver, logger, getClass().getClassLoader());

        ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> active = new ConcurrentHashMap<>();
        Map<DatabaseConnectionKey, Integer> refs = Map.of();
        when(registry.getActiveDatabases()).thenReturn(active);
        when(registry.getActiveDatabaseReferenceCounts()).thenReturn(refs);

        assertSame(active, handler.getActiveDatabases());
        assertSame(refs, handler.getActiveDatabaseReferenceCounts());
        handler.shutdownAllDatabases();

        verify(registry).shutdownAllDatabases();
    }

    @Test
    void operationsFailFastWhenRegistryIsClosed() {
        DataProviderRegistry registry = mock(DataProviderRegistry.class);
        when(registry.isClosed()).thenReturn(true);

        ClassLoader pluginLoader = new ClassLoader() {
        };
        CallerContextResolver resolver = () -> new CallerContext("plugin", pluginLoader);
        DataProviderHandler handler = new DataProviderHandler(
                registry,
                resolver,
                new RecordingLoggerAdapter(),
                getClass().getClassLoader()
        );

        assertThrows(IllegalStateException.class, () -> handler.registerDatabase(DatabaseType.MYSQL, "default"));
        assertThrows(IllegalStateException.class, () ->
                handler.registerDatabaseForScope("component.scope", DatabaseType.MYSQL, "default"));
        assertThrows(IllegalStateException.class, () -> handler.getRegisteredDatabase(DatabaseType.MYSQL, "default"));
        assertThrows(IllegalStateException.class, () -> handler.unregisterDatabase(DatabaseType.MYSQL, "default"));
        assertThrows(IllegalStateException.class, () ->
                handler.unregisterDatabaseForScope("component.scope", DatabaseType.MYSQL, "default"));
        assertThrows(IllegalStateException.class, handler::unregisterAllDatabases);
        assertThrows(IllegalStateException.class, () -> handler.unregisterAllDatabasesForScope("component.scope"));
        assertThrows(IllegalStateException.class, handler::unregisterAllDatabasesForPlugin);
        assertThrows(IllegalStateException.class, handler::getActiveDatabases);
        assertThrows(IllegalStateException.class, handler::getActiveDatabaseReferenceCounts);

        verify(registry, never()).registerDatabase(
                PluginId.of("plugin"),
                OwnerScopeId.of("plugin"),
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("default")
        );
        verify(registry, never()).registerDatabase(
                PluginId.of("plugin"),
                OwnerScopeId.of("component.scope"),
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("default")
        );
        verify(registry, never()).getDatabase(
                PluginId.of("plugin"),
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("default")
        );
        verify(registry, never()).unregisterDatabase(
                PluginId.of("plugin"),
                OwnerScopeId.of("plugin"),
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("default")
        );
        verify(registry, never()).unregisterDatabase(
                PluginId.of("plugin"),
                OwnerScopeId.of("component.scope"),
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("default")
        );
        verify(registry, never()).unregisterAllDatabases(PluginId.of("plugin"), OwnerScopeId.of("plugin"));
        verify(registry, never()).unregisterAllDatabases(PluginId.of("plugin"), OwnerScopeId.of("component.scope"));
        verify(registry, never()).unregisterAllDatabasesForPlugin(PluginId.of("plugin"));
    }
}
