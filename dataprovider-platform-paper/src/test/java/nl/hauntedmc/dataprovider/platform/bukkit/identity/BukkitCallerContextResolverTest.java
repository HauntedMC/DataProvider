package nl.hauntedmc.dataprovider.platform.bukkit.identity;

import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BukkitCallerContextResolverTest {

    @Test
    void invalidatesLifecycleIdentityButRetainsInstalledPluginKnowledge() {
        Plugin plugin = mock(Plugin.class);
        BukkitCallerContextResolver resolver = resolverFor(plugin.getClass().getClassLoader());
        when(plugin.getName()).thenReturn("Example");
        when(plugin.isEnabled()).thenReturn(true);
        resolver.register(plugin);

        PluginIdentity identity = resolver.issueIdentity(plugin);
        clearInvocations(plugin);

        assertSame(plugin.getClass().getClassLoader(), identity.classLoader());
        assertTrue(resolver.isKnownPlugin("example"));
        assertTrue(resolver.isIdentityActive(identity));
        verifyNoInteractions(plugin);

        resolver.invalidate(plugin);
        assertFalse(resolver.isIdentityActive(identity));
        assertTrue(resolver.isKnownPlugin("example"));
    }

    @Test
    void invalidateAllClearsLifecycleAndInstalledPluginKnowledge() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("Example");
        BukkitCallerContextResolver resolver = resolverFor(plugin.getClass().getClassLoader());
        PluginIdentity identity = resolver.register(plugin);

        resolver.invalidateAll();

        assertFalse(resolver.isIdentityActive(identity));
        assertFalse(resolver.isKnownPlugin("example"));
    }

    @Test
    void synchronizesInstalledPluginsBeforeTheyAreEnabled() {
        Plugin dataRegistryDelegate = mock(Plugin.class);
        Plugin serverFeaturesDelegate = mock(Plugin.class);
        when(dataRegistryDelegate.getName()).thenReturn("DataRegistry");
        when(serverFeaturesDelegate.getName()).thenReturn("ServerFeatures");
        when(dataRegistryDelegate.isEnabled()).thenReturn(true);
        when(serverFeaturesDelegate.isEnabled()).thenReturn(false);
        ClassLoader dataRegistryLoader = new ClassLoader() {
        };
        ClassLoader serverFeaturesLoader = new ClassLoader() {
        };
        Plugin dataRegistry = pluginWithLoader(dataRegistryDelegate, dataRegistryLoader);
        Plugin serverFeatures = pluginWithLoader(serverFeaturesDelegate, serverFeaturesLoader);

        BukkitCallerContextResolver resolver = resolverFor(dataRegistryLoader);
        resolver.synchronizePlugins(List.of(dataRegistry, serverFeatures));

        assertTrue(resolver.isKnownPlugin("dataregistry"));
        assertTrue(resolver.isKnownPlugin("serverfeatures"));
        assertNull(resolver.find(serverFeatures));
        assertThrows(SecurityException.class, () -> resolver.issueIdentity(serverFeatures));
    }

    @Test
    void issuesAnIdentityDuringPluginEnableBeforeTheLifecycleEventIsFired() {
        Plugin plugin = mock(Plugin.class);
        BukkitCallerContextResolver resolver = resolverFor(plugin.getClass().getClassLoader());
        when(plugin.getName()).thenReturn("Example");
        when(plugin.isEnabled()).thenReturn(true);

        PluginIdentity identity = resolver.issueIdentity(plugin);

        assertTrue(resolver.isIdentityActive(identity));
        assertSame(identity, resolver.register(plugin));
    }

    @Test
    void rejectsObjectsThatAreNotBukkitPlugins() {
        BukkitCallerContextResolver resolver = resolverFor(getClass().getClassLoader());
        assertThrows(SecurityException.class, () -> resolver.issueIdentity(new Object()));
    }

    @Test
    void rejectsBindingAnotherPluginsInstance() {
        Plugin owner = mock(Plugin.class);
        Plugin attacker = mock(Plugin.class);
        when(owner.getName()).thenReturn("Owner");
        when(owner.isEnabled()).thenReturn(true);
        when(attacker.getName()).thenReturn("Attacker");
        when(attacker.isEnabled()).thenReturn(true);
        ClassLoader ownerLoader = new ClassLoader() {
        };
        ClassLoader attackerLoader = new ClassLoader() {
        };
        owner = pluginWithLoader(owner, ownerLoader);
        attacker = pluginWithLoader(attacker, attackerLoader);
        BukkitCallerContextResolver resolver = new BukkitCallerContextResolver(
                getClass().getClassLoader(),
                () -> List.of(attackerLoader)
        );
        resolver.register(owner);
        resolver.register(attacker);

        Plugin victim = owner;
        assertThrows(SecurityException.class, () -> resolver.issueIdentity(victim));
    }

    private BukkitCallerContextResolver resolverFor(ClassLoader callerLoader) {
        return new BukkitCallerContextResolver(
                getClass().getClassLoader(),
                () -> List.of(callerLoader)
        );
    }

    private static Plugin pluginWithLoader(Plugin delegate, ClassLoader classLoader) {
        return (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                classLoader,
                new Class<?>[] {Plugin.class},
                (proxy, method, arguments) -> method.invoke(delegate, arguments)
        );
    }
}
