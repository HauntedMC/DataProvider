package nl.hauntedmc.dataprovider.platform.velocity.identity;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.ProxyServer;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VelocityCallerContextResolverTest {

    @Test
    void issuesAndValidatesCapturedIdentityWithoutFurtherPluginManagerAccess() {
        ClassLoader nearestLoader = new ClassLoader() {
        };
        ClassLoader outerLoader = new ClassLoader() {
        };

        Object plugin = createPluginInstance(nearestLoader);
        ProxyServer proxy = createProxyServer(
                        createPluginContainer("proxyfeatures", plugin),
                        createPluginContainer("wrapperplugin", createPluginInstance(outerLoader))
                );
        VelocityCallerContextResolver resolver = new VelocityCallerContextResolver(
                proxy,
                getClass().getClassLoader(),
                () -> List.of(nearestLoader)
        );
        resolver.synchronizePlugins();
        clearInvocations(proxy);

        PluginIdentity identity = resolver.issueIdentity(plugin);
        CompletableFuture.runAsync(() -> assertTrue(resolver.isIdentityActive(identity))).join();

        assertSame(nearestLoader, identity.classLoader());
        assertTrue(resolver.isIdentityActive(identity));
        verifyNoInteractions(proxy);
        resolver.invalidateAll();
        assertFalse(resolver.isIdentityActive(identity));
    }

    @Test
    void rejectsAnUnregisteredInstanceFromTheRegisteredClassLoader() {
        ClassLoader pluginLoader = new ClassLoader() {
        };
        Object registered = createPluginInstance(pluginLoader);
        ProxyServer proxy = createProxyServer(createPluginContainer("example", registered));
        VelocityCallerContextResolver resolver = new VelocityCallerContextResolver(
                proxy,
                getClass().getClassLoader(),
                () -> List.of(pluginLoader)
        );
        resolver.synchronizePlugins();

        Object lookalike = createPluginInstance(pluginLoader);
        assertThrows(SecurityException.class, () -> resolver.issueIdentity(lookalike));
    }

    @Test
    void repeatedSynchronizationPreservesIssuedIdentities() {
        ClassLoader pluginLoader = new ClassLoader() {
        };
        Object plugin = createPluginInstance(pluginLoader);
        ProxyServer proxy = createProxyServer(createPluginContainer("example", plugin));
        VelocityCallerContextResolver resolver = new VelocityCallerContextResolver(
                proxy,
                getClass().getClassLoader(),
                () -> List.of(pluginLoader)
        );

        resolver.synchronizePlugins();
        PluginIdentity issued = resolver.issueIdentity(plugin);
        resolver.synchronizePlugins();

        assertSame(issued, resolver.issueIdentity(plugin));
        assertTrue(resolver.isIdentityActive(issued));
    }

    @Test
    void rejectsBindingAnotherPluginsInstance() {
        ClassLoader ownerLoader = new ClassLoader() {
        };
        ClassLoader attackerLoader = new ClassLoader() {
        };
        Object owner = createPluginInstance(ownerLoader);
        Object attacker = createPluginInstance(attackerLoader);
        ProxyServer proxy = createProxyServer(
                createPluginContainer("owner", owner),
                createPluginContainer("attacker", attacker)
        );
        VelocityCallerContextResolver resolver = new VelocityCallerContextResolver(
                proxy,
                getClass().getClassLoader(),
                () -> List.of(attackerLoader)
        );
        resolver.synchronizePlugins();

        assertThrows(SecurityException.class, () -> resolver.issueIdentity(owner));
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
