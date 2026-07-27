package nl.hauntedmc.dataprovider.core;

import nl.hauntedmc.dataprovider.core.identity.CallerContext;
import nl.hauntedmc.dataprovider.core.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityRegistry;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityState;
import nl.hauntedmc.dataprovider.exception.ProviderClosedException;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataProviderHandlerIdentityTest {

    @Test
    void rejectsBoundHandleWhenAnotherPluginCallerIsPresent() {
        ClassLoader ownerLoader = new ClassLoader() {
        };
        ClassLoader attackerLoader = new ClassLoader() {
        };
        PluginIdentity identity = new PluginIdentityRegistry().register("owner", ownerLoader);
        CallerContextResolver resolver = mock(CallerContextResolver.class);
        when(resolver.identityState(identity)).thenReturn(PluginIdentityState.ACTIVE);
        when(resolver.resolveCallerIfPresent()).thenReturn(new CallerContext("attacker", attackerLoader));
        DataProviderHandler handler = handler(resolver);

        assertThrows(SecurityException.class, () -> handler.requireIdentity(identity));
    }

    @Test
    void allowsBoundHandleOnGenericWorkerWithoutAPluginCaller() {
        ClassLoader ownerLoader = new ClassLoader() {
        };
        PluginIdentity identity = new PluginIdentityRegistry().register("owner", ownerLoader);
        CallerContextResolver resolver = mock(CallerContextResolver.class);
        when(resolver.identityState(identity)).thenReturn(PluginIdentityState.ACTIVE);
        when(resolver.resolveCallerIfPresent()).thenReturn(null);
        DataProviderHandler handler = handler(resolver);

        assertDoesNotThrow(() -> handler.requireIdentity(identity));
    }

    @Test
    void disablingIdentityRejectsNewWorkButAllowsCleanup() {
        PluginIdentity identity = new PluginIdentityRegistry().register("owner", new ClassLoader() {
        });
        CallerContextResolver resolver = mock(CallerContextResolver.class);
        when(resolver.identityState(identity)).thenReturn(PluginIdentityState.DISABLING);
        DataProviderHandler handler = handler(resolver);

        assertThrows(SecurityException.class, () -> handler.requireIdentity(identity));
        assertDoesNotThrow(() -> handler.requireIdentityForCleanup(identity));
    }

    @Test
    void cleanupStillRejectsAnotherPluginCaller() {
        ClassLoader ownerLoader = new ClassLoader() {
        };
        ClassLoader attackerLoader = new ClassLoader() {
        };
        PluginIdentity identity = new PluginIdentityRegistry().register("owner", ownerLoader);
        CallerContextResolver resolver = mock(CallerContextResolver.class);
        when(resolver.identityState(identity)).thenReturn(PluginIdentityState.DISABLING);
        when(resolver.resolveCallerForCleanupIfPresent())
                .thenReturn(new CallerContext("attacker", attackerLoader));
        DataProviderHandler handler = handler(resolver);

        assertThrows(SecurityException.class, () -> handler.requireIdentityForCleanup(identity));
    }

    @Test
    void pluginIdentityLookupReportsItsOwnOperationWhenClosed() {
        DataProviderRegistry registry = mock(DataProviderRegistry.class);
        when(registry.isClosed()).thenReturn(true);
        DataProviderHandler handler = new DataProviderHandler(
                registry,
                mock(CallerContextResolver.class),
                mock(LoggerAdapter.class),
                getClass().getClassLoader()
        );
        PluginIdentity identity =
                new PluginIdentityRegistry().register("owner", new ClassLoader() {
                });

        ProviderClosedException failure =
                assertThrows(ProviderClosedException.class, () -> handler.getPluginId(identity));
        assertEquals("getPluginId", failure.operationName());
    }

    @Test
    void pluginOrmSchemaModeComesFromTheManagedConfiguration() {
        ClassLoader ownerLoader = new ClassLoader() {
        };
        PluginIdentity identity = new PluginIdentityRegistry().register("owner", ownerLoader);
        CallerContextResolver resolver = mock(CallerContextResolver.class);
        when(resolver.identityState(identity)).thenReturn(PluginIdentityState.ACTIVE);
        DataProviderRegistry registry = mock(DataProviderRegistry.class);
        when(registry.getOrmSchemaMode()).thenReturn("validate");
        DataProviderHandler handler = new DataProviderHandler(
                registry,
                resolver,
                mock(LoggerAdapter.class),
                getClass().getClassLoader()
        );

        assertEquals("validate", handler.getConfiguredOrmSchemaMode(identity));
    }

    private DataProviderHandler handler(CallerContextResolver resolver) {
        return new DataProviderHandler(
                mock(DataProviderRegistry.class),
                resolver,
                mock(LoggerAdapter.class),
                getClass().getClassLoader()
        );
    }
}
