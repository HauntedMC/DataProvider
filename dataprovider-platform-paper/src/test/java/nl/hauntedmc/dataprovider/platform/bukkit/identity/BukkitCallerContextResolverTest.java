package nl.hauntedmc.dataprovider.platform.bukkit.identity;

import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BukkitCallerContextResolverTest {

    @Test
    void issuesAndInvalidatesLifecycleIdentityWithoutBukkitAccessDuringUse() {
        BukkitCallerContextResolver resolver = new BukkitCallerContextResolver(getClass().getClassLoader());
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("Example");
        resolver.register(plugin);

        PluginIdentity identity = resolver.issueIdentity(plugin);
        clearInvocations(plugin);

        assertSame(plugin.getClass().getClassLoader(), identity.classLoader());
        assertTrue(resolver.isKnownPlugin("example"));
        assertTrue(resolver.isIdentityActive(identity));
        verifyNoInteractions(plugin);

        resolver.invalidate(plugin);
        assertFalse(resolver.isIdentityActive(identity));
        assertFalse(resolver.isKnownPlugin("example"));
    }

    @Test
    void rejectsObjectsThatAreNotBukkitPlugins() {
        BukkitCallerContextResolver resolver = new BukkitCallerContextResolver(getClass().getClassLoader());
        assertThrows(SecurityException.class, () -> resolver.issueIdentity(new Object()));
    }
}
