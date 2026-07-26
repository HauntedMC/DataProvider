package nl.hauntedmc.dataprovider.platform.bukkit.identity;

import nl.hauntedmc.dataprovider.core.identity.CallerContext;
import nl.hauntedmc.dataprovider.core.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.core.identity.PluginCallerChainResolver;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityRegistry;
import nl.hauntedmc.dataprovider.core.identity.StackCallerClassLoaderResolver;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bukkit-specific caller identity resolver.
 */
public final class BukkitCallerContextResolver implements CallerContextResolver {

    private final PluginIdentityRegistry identities = new PluginIdentityRegistry();
    private final Supplier<List<ClassLoader>> callerChain;

    public BukkitCallerContextResolver(ClassLoader ownClassLoader) {
        this(
                ownClassLoader,
                () -> StackCallerClassLoaderResolver.resolveExternalCallerChain(ownClassLoader)
        );
    }

    BukkitCallerContextResolver(ClassLoader ownClassLoader, Supplier<List<ClassLoader>> callerChain) {
        Objects.requireNonNull(ownClassLoader, "Own class loader cannot be null.");
        this.callerChain = Objects.requireNonNull(callerChain, "Caller chain cannot be null.");
    }

    @Override
    public CallerContext resolveCaller() {
        return PluginCallerChainResolver.resolveNearestMappedCaller(
                callerChain.get(),
                classLoader -> {
                    PluginIdentity identity = identities.find(classLoader);
                    return identity == null ? null : identity.pluginId();
                },
                "Could not resolve the calling Bukkit plugin."
        );
    }

    @Override
    public CallerContext resolveCallerIfPresent() {
        return PluginCallerChainResolver.findNearestMappedCaller(
                callerChain.get(),
                classLoader -> {
                    PluginIdentity identity = identities.find(classLoader);
                    return identity == null ? null : identity.pluginId();
                }
        );
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
        requireBindingCaller(identity);
        return identity;
    }

    @Override
    public boolean isIdentityActive(PluginIdentity identity) {
        return identities.isActive(identity);
    }

    private void requireBindingCaller(PluginIdentity identity) {
        CallerContext caller = resolveCaller();
        if (!identity.pluginId().equals(caller.pluginId()) || identity.classLoader() != caller.classLoader()) {
            throw new SecurityException("A Bukkit plugin can bind DataProvider only to its own plugin instance.");
        }
    }
}
