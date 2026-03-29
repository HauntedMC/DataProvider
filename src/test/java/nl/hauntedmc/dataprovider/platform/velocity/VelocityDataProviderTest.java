package nl.hauntedmc.dataprovider.platform.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ProxyServer;
import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
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
    void dataProviderApiThrowsWhenNotInitialized() {
        VelocityDataProvider provider = new VelocityDataProvider(
                mock(ProxyServer.class),
                mock(Logger.class),
                Path.of(".")
        );

        assertThrows(IllegalStateException.class, provider::dataProviderApi);
    }

    @Test
    void dataProviderApiReturnsStoredApiWhenInitialized() throws ReflectiveOperationException {
        nl.hauntedmc.dataprovider.internal.DataProviderHandler handler =
                mock(nl.hauntedmc.dataprovider.internal.DataProviderHandler.class);

        VelocityDataProvider velocityDataProvider = new VelocityDataProvider(
                mock(ProxyServer.class),
                mock(Logger.class),
                Path.of(".")
        );

        Field field = VelocityDataProvider.class.getDeclaredField("dataProviderApi");
        field.setAccessible(true);
        field.set(velocityDataProvider, new DataProviderAPI(handler));

        DataProviderAPI api = velocityDataProvider.dataProviderApi();
        assertNotNull(api);
        api.unregisterAllDatabases();
        verify(handler).unregisterAllDatabases();
    }
}
