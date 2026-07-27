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
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Bukkit-specific caller identity resolver.
 */
public final class BukkitCallerContextResolver implements CallerContextResolver {

    private final PluginIdentityRegistry identities = new PluginIdentityRegistry();
    private final Set<String> installedPluginIds = ConcurrentHashMap.newKeySet();
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
        if (pluginId == null || pluginId.isBlank()) {
            return false;
        }
        return installedPluginIds.contains(normalizePluginId(pluginId));
    }

    /**
     * Called from Paper's lifecycle thread before APIs are handed to plugins.
     *
     * <p>All installed plugins must be known here, not only plugins that have already completed
     * {@code onEnable}. Access policies are configuration declarations and may legitimately name a
     * plugin that enables later in Paper's dependency order. API binding still requires the plugin
     * to be enabled, so registering its identity here does not allow premature API use.</p>
     */
    public void synchronizePlugins() {
        synchronizePlugins(List.of(Bukkit.getPluginManager().getPlugins()));
    }

    void synchronizePlugins(Iterable<? extends Plugin> plugins) {
        Objects.requireNonNull(plugins, "Plugins cannot be null.");
        for (Plugin plugin : plugins) {
            register(plugin);
        }
    }

    public PluginIdentity register(Plugin plugin) {
        Objects.requireNonNull(plugin, "Plugin cannot be null.");
        String pluginId = normalizePluginId(plugin.getName());
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        PluginIdentity existing = identities.find(classLoader);
        PluginIdentity identity = existing != null ? existing : identities.register(pluginId, classLoader);
        if (!identity.pluginId().equals(pluginId)) {
            throw new IllegalStateException(
                    "Cannot securely distinguish Bukkit plugins '" + identity.pluginId() + "' and '"
                            + pluginId + "' because they share one class loader."
            );
        }
        installedPluginIds.add(pluginId);
        return identity;
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
        installedPluginIds.clear();
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
        if (!identity.pluginId().equals(normalizePluginId(plugin.getName()))) {
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

    private static String normalizePluginId(String pluginId) {
        String normalized = Objects.requireNonNull(pluginId, "Plugin id cannot be null.")
                .trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Plugin id cannot be blank.");
        }
        return normalized;
    }
}
