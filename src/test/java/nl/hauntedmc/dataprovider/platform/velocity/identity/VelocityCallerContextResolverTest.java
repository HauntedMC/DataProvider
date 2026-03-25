package nl.hauntedmc.dataprovider.platform.velocity.identity;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ProxyServer;
import nl.hauntedmc.dataprovider.internal.identity.CallerContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VelocityCallerContextResolverTest {

    @Test
    void resolvesNearestPluginFromCallerChain() {
        ClassLoader nearestLoader = new ClassLoader() {
        };
        ClassLoader outerLoader = new ClassLoader() {
        };

        VelocityCallerContextResolver resolver = new VelocityCallerContextResolver(
                createProxyServer(
                        createPluginContainer("proxyfeatures", createPluginInstance(nearestLoader)),
                        createPluginContainer("wrapperplugin", createPluginInstance(outerLoader))
                ),
                getClass().getClassLoader()
        );

        CallerContext callerContext = resolver.resolveCaller(List.of(nearestLoader, outerLoader));

        assertEquals("proxyfeatures", callerContext.pluginId());
        assertSame(nearestLoader, callerContext.classLoader());
    }

    private static ProxyServer createProxyServer(PluginContainer... pluginContainers) {
        ProxyServer proxyServer = mock(ProxyServer.class);
        PluginManager pluginManager = mock(PluginManager.class);
        when(proxyServer.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.getPlugins()).thenReturn(List.of(pluginContainers));
        return proxyServer;
    }

    private static PluginContainer createPluginContainer(String pluginId, Object pluginInstance) {
        PluginContainer container = mock(PluginContainer.class);
        PluginDescription description = mock(PluginDescription.class);
        doReturn(Optional.of(pluginInstance)).when(container).getInstance();
        when(container.getDescription()).thenReturn(description);
        when(description.getId()).thenReturn(pluginId);
        return container;
    }

    private static Object createPluginInstance(ClassLoader classLoader) {
        return Proxy.newProxyInstance(classLoader, new Class[]{Runnable.class}, (proxy, method, args) -> null);
    }
}
