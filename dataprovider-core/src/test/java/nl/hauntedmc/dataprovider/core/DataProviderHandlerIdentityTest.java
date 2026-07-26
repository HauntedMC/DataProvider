package nl.hauntedmc.dataprovider.core;

import nl.hauntedmc.dataprovider.core.identity.CallerContext;
import nl.hauntedmc.dataprovider.core.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityRegistry;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import nl.hauntedmc.dataprovider.exception.ProviderClosedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        when(resolver.isIdentityActive(identity)).thenReturn(true);
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
        when(resolver.isIdentityActive(identity)).thenReturn(true);
        when(resolver.resolveCallerIfPresent()).thenReturn(null);
        DataProviderHandler handler = handler(resolver);

        assertDoesNotThrow(() -> handler.requireIdentity(identity));
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

    private DataProviderHandler handler(CallerContextResolver resolver) {
        return new DataProviderHandler(
                mock(DataProviderRegistry.class),
                resolver,
                mock(LoggerAdapter.class),
                getClass().getClassLoader()
        );
    }
}
