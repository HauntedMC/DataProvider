package nl.hauntedmc.dataprovider.platform.bukkit.identity;

import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityState;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
        assertEquals(PluginIdentityState.INACTIVE, resolver.identityState(identity));
        assertTrue(resolver.isKnownPlugin("example"));
    }

    @Test
    void disablingGenerationRemainsResolvableOnlyForCleanup() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("Example");
        when(plugin.isEnabled()).thenReturn(true);
        ClassLoader loader = plugin.getClass().getClassLoader();
        BukkitCallerContextResolver resolver = resolverFor(loader);
        PluginIdentity identity = resolver.register(plugin);

        assertSame(identity, resolver.beginDisable(plugin));

        assertEquals(PluginIdentityState.DISABLING, resolver.identityState(identity));
        assertNull(resolver.resolveCallerIfPresent());
        assertEquals("example", resolver.resolveCallerForCleanup().pluginId());
        assertThrows(IllegalStateException.class, () -> resolver.issueIdentity(plugin));
        assertEquals(PluginIdentityState.DISABLING, resolver.identityState(identity));
    }

    @Test
    void successfulFinalizationAllowsOnEnableBindingToCreateANewGeneration() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("Example");
        when(plugin.isEnabled()).thenReturn(true);
        BukkitCallerContextResolver resolver = resolverFor(plugin.getClass().getClassLoader());
        PluginIdentity disabling = resolver.register(plugin);
        resolver.beginDisable(plugin);
        AtomicInteger finalizations = new AtomicInteger();
        resolver.setDisableFinalizer((target, identity) -> {
            finalizations.incrementAndGet();
            return resolver.invalidate(target, identity);
        });

        PluginIdentity replacement = resolver.issueIdentity(plugin);

        assertEquals(1, finalizations.get());
        assertNotSame(disabling, replacement);
        assertEquals(PluginIdentityState.INACTIVE, resolver.identityState(disabling));
        assertEquals(PluginIdentityState.ACTIVE, resolver.identityState(replacement));
        assertSame(replacement, resolver.find(plugin));
    }

    @Test
    void failedFinalizationBlocksReactivationAndRetainsCleanupCapability() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("Example");
        when(plugin.isEnabled()).thenReturn(true);
        ClassLoader loader = plugin.getClass().getClassLoader();
        BukkitCallerContextResolver resolver = resolverFor(loader);
        PluginIdentity disabling = resolver.register(plugin);
        resolver.beginDisable(plugin);
        AtomicInteger finalizations = new AtomicInteger();
        resolver.setDisableFinalizer((target, identity) -> {
            finalizations.incrementAndGet();
            return false;
        });

        assertThrows(IllegalStateException.class, () -> resolver.register(plugin));

        assertEquals(1, finalizations.get());
        assertSame(disabling, resolver.find(plugin));
        assertEquals(PluginIdentityState.DISABLING, resolver.identityState(disabling));
        assertEquals("example", resolver.resolveCallerForCleanup().pluginId());
    }

    @Test
    void delayedDisableFinalizationCannotInvalidateReenabledGeneration() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("Example");
        when(plugin.isEnabled()).thenReturn(true);
        BukkitCallerContextResolver resolver = resolverFor(plugin.getClass().getClassLoader());
        PluginIdentity disabling = resolver.register(plugin);
        resolver.beginDisable(plugin);
        resolver.setDisableFinalizer(resolver::invalidate);
        PluginIdentity replacement = resolver.register(plugin);

        assertFalse(resolver.invalidate(plugin, disabling));

        assertEquals(PluginIdentityState.INACTIVE, resolver.identityState(disabling));
        assertEquals(PluginIdentityState.ACTIVE, resolver.identityState(replacement));
        assertSame(replacement, resolver.find(plugin));
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
