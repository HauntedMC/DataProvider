package nl.hauntedmc.dataprovider.platform.velocity;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ProxyServer;
import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VelocityDataProviderTest {

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
        DataProvider original = swapStaticDataProvider(null);
        try {
            assertThrows(IllegalStateException.class, VelocityDataProvider::getDataProviderAPI);
        } finally {
            swapStaticDataProvider(original);
        }
    }

    @Test
    void getDataProviderApiReturnsFacadeWhenInitialized() throws ReflectiveOperationException {
        DataProvider provider = mock(DataProvider.class);
        DataProviderHandler handler = mock(DataProviderHandler.class);
        when(provider.getDataProviderHandler()).thenReturn(handler);

        DataProvider original = swapStaticDataProvider(provider);
        try {
            DataProviderAPI api = VelocityDataProvider.getDataProviderAPI();
            assertNotNull(api);
            api.unregisterAllDatabases();
            verify(handler).unregisterAllDatabases();
        } finally {
            swapStaticDataProvider(original);
        }
    }

    private static DataProvider swapStaticDataProvider(DataProvider replacement) throws ReflectiveOperationException {
        Field field = VelocityDataProvider.class.getDeclaredField("dataProvider");
        field.setAccessible(true);
        DataProvider previous = (DataProvider) field.get(null);
        field.set(null, replacement);
        return previous;
    }
}
