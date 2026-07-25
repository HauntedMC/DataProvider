package nl.hauntedmc.dataprovider.platform.bukkit.identity;

import nl.hauntedmc.dataprovider.core.identity.CallerContext;
import nl.hauntedmc.dataprovider.core.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityRegistry;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

/**
 * Bukkit-specific caller identity resolver.
 */
public final class BukkitCallerContextResolver implements CallerContextResolver {

    private final ClassLoader ownClassLoader;
    private final PluginIdentityRegistry identities = new PluginIdentityRegistry();

    public BukkitCallerContextResolver(ClassLoader ownClassLoader) {
        this.ownClassLoader = Objects.requireNonNull(ownClassLoader, "Own class loader cannot be null.");
    }

    @Override
    public CallerContext resolveCaller() {
        throw new SecurityException("Use DataProviderAPI.forPlugin(plugin) to obtain a bound API facade.");
    }

    @Override
    public boolean isKnownPlugin(String pluginId) {
        return identities.isKnownPlugin(pluginId);
    }

    /** Called from Paper's lifecycle thread before APIs are handed to plugins. */
    public void synchronizePlugins() {
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.isEnabled()) {
                register(plugin);
            }
        }
    }

    public PluginIdentity register(Plugin plugin) {
        Objects.requireNonNull(plugin, "Plugin cannot be null.");
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        PluginIdentity existing = identities.find(classLoader);
        return existing != null ? existing : identities.register(plugin.getName(), classLoader);
    }

    public void invalidate(Plugin plugin) {
        if (plugin != null) {
            identities.invalidate(plugin.getClass().getClassLoader());
        }
    }

    public PluginIdentity find(Plugin plugin) {
        return plugin == null ? null : identities.find(plugin.getClass().getClassLoader());
    }

    public void invalidateAll() {
        identities.invalidateAll();
    }

    @Override
    public PluginIdentity issueIdentity(Object platformPlugin) {
        if (!(platformPlugin instanceof Plugin plugin)) {
            throw new SecurityException("DataProvider requires a Bukkit Plugin instance for API binding.");
        }
        if (!plugin.isEnabled()) {
            throw new SecurityException("DataProvider requires an enabled Bukkit Plugin instance for API binding.");
        }
        // Bukkit fires PluginEnableEvent after JavaPlugin.onEnable. Register here as
        // well so a plugin can bind the API from its own onEnable callback.
        PluginIdentity identity = register(plugin);
        if (identity == null || !identity.pluginId().equals(plugin.getName().trim().toLowerCase(java.util.Locale.ROOT))) {
            throw new SecurityException("Bukkit plugin is not active in DataProvider's identity registry.");
        }
        return identity;
    }

    @Override
    public boolean isIdentityActive(PluginIdentity identity) {
        return identities.isActive(identity);
    }
}
