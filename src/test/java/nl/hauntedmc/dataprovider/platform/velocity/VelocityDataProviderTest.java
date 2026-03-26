package nl.hauntedmc.dataprovider.platform.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ProxyServer;
import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
import nl.hauntedmc.dataprovider.platform.common.lifecycle.PlatformDataProviderRuntime;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VelocityDataProviderTest {

    @Test
    void lifecycleHandlersUseDeterministicVelocityEventOrder() throws ReflectiveOperationException {
        Method initializeHandler = VelocityDataProvider.class.getDeclaredMethod(
                "onProxyInitialize",
                ProxyInitializeEvent.class
        );
        Subscribe initializeSubscribe = initializeHandler.getAnnotation(Subscribe.class);
        assertNotNull(initializeSubscribe);
        assertEquals(Short.MAX_VALUE, initializeSubscribe.priority());

        Method shutdownHandler = VelocityDataProvider.class.getDeclaredMethod(
                "onProxyShutdown",
                ProxyShutdownEvent.class
        );
        Subscribe shutdownSubscribe = shutdownHandler.getAnnotation(Subscribe.class);
        assertNotNull(shutdownSubscribe);
        assertEquals(Short.MIN_VALUE, shutdownSubscribe.priority());
    }

    @Test
    void resolvePluginVersionReturnsDescriptionVersionValue() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        PluginManager pluginManager = mock(PluginManager.class);
        PluginContainer pluginContainer = mock(PluginContainer.class);
        PluginDescription pluginDescription = mock(PluginDescription.class);
        Object pluginInstance = new Object();

        when(proxyServer.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.fromInstance(pluginInstance)).thenReturn(Optional.of(pluginContainer));
        when(pluginContainer.getDescription()).thenReturn(pluginDescription);
        when(pluginDescription.getVersion()).thenReturn(Optional.of("1.20.4"));

        assertEquals("1.20.4", VelocityDataProvider.resolvePluginVersion(proxyServer, pluginInstance));
    }

    @Test
    void resolvePluginVersionFallsBackToUnknownWhenVersionMissing() {
        ProxyServer proxyServer = mock(ProxyServer.class);
        PluginManager pluginManager = mock(PluginManager.class);
        PluginContainer pluginContainer = mock(PluginContainer.class);
        PluginDescription pluginDescription = mock(PluginDescription.class);
        Object pluginInstance = new Object();

        when(proxyServer.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.fromInstance(pluginInstance)).thenReturn(Optional.of(pluginContainer));
        when(pluginContainer.getDescription()).thenReturn(pluginDescription);
        when(pluginDescription.getVersion()).thenReturn(Optional.empty());

        assertEquals("unknown", VelocityDataProvider.resolvePluginVersion(proxyServer, pluginInstance));
    }

    @Test
    void getDataProviderApiThrowsWhenNotInitialized() throws ReflectiveOperationException {
        PlatformDataProviderRuntime runtime = resolveRuntime();
        runtime.stop(mock(ILoggerAdapter.class));

        assertThrows(IllegalStateException.class, VelocityDataProvider::getDataProviderAPI);
    }

    @Test
    void getDataProviderApiReturnsFacadeWhenInitialized() throws ReflectiveOperationException {
        DataProvider provider = mock(DataProvider.class);
        DataProviderHandler handler = mock(DataProviderHandler.class);
        when(provider.getDataProviderHandler()).thenReturn(handler);

        PlatformDataProviderRuntime runtime = resolveRuntime();
        runtime.stop(mock(ILoggerAdapter.class));
        runtime.start(() -> provider, mock(ILoggerAdapter.class));
        try {
            DataProviderAPI api = VelocityDataProvider.getDataProviderAPI();
            assertNotNull(api);
            api.unregisterAllDatabases();
            verify(handler).unregisterAllDatabases();
        } finally {
            runtime.stop(mock(ILoggerAdapter.class));
        }
    }

    private static PlatformDataProviderRuntime resolveRuntime() throws ReflectiveOperationException {
        Field field = VelocityDataProvider.class.getDeclaredField("RUNTIME");
        field.setAccessible(true);
        return (PlatformDataProviderRuntime) field.get(null);
    }
}
