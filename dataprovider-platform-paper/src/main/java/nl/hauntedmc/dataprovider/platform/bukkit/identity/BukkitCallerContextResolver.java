package nl.hauntedmc.dataprovider.platform.bukkit.identity;

import nl.hauntedmc.dataprovider.core.identity.CallerContext;
import nl.hauntedmc.dataprovider.core.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.core.identity.PluginCallerChainResolver;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityRegistry;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityState;
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
        return resolveCaller(false);
    }

    @Override
    public CallerContext resolveCallerIfPresent() {
        return findCaller(false);
    }

    @Override
    public CallerContext resolveCallerForCleanup() {
        return resolveCaller(true);
    }

    @Override
    public CallerContext resolveCallerForCleanupIfPresent() {
        return findCaller(true);
    }

    private CallerContext resolveCaller(boolean cleanup) {
        return PluginCallerChainResolver.resolveNearestMappedCaller(
                callerChain.get(),
                classLoader -> pluginIdFor(classLoader, cleanup),
                cleanup
                        ? "Could not resolve the calling Bukkit plugin for cleanup."
                        : "Could not resolve the calling Bukkit plugin."
        );
    }

    private CallerContext findCaller(boolean cleanup) {
        return PluginCallerChainResolver.findNearestMappedCaller(
                callerChain.get(),
                classLoader -> pluginIdFor(classLoader, cleanup)
        );
    }

    private String pluginIdFor(ClassLoader classLoader, boolean cleanup) {
        PluginIdentity identity = identities.find(classLoader);
        if (identity == null) {
            return null;
        }
        PluginIdentityState state = identities.stateOf(identity);
        return state == PluginIdentityState.ACTIVE || cleanup && state.permitsCleanup()
                ? identity.pluginId()
                : null;
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
     * <p>All installed plugin names must be known here, not only plugins that have already completed
     * {@code onEnable}. Access policies are configuration declarations and may legitimately name a
     * plugin that enables later in Paper's dependency order. Only enabled plugins receive an active
     * lifecycle identity; disabled plugins are known solely for configuration validation.</p>
     */
    public void synchronizePlugins() {
        synchronizePlugins(List.of(Bukkit.getPluginManager().getPlugins()));
    }

    void synchronizePlugins(Iterable<? extends Plugin> plugins) {
        Objects.requireNonNull(plugins, "Plugins cannot be null.");
        for (Plugin plugin : plugins) {
            rememberInstalled(plugin);
            if (plugin.isEnabled()) {
                registerActiveIdentity(plugin);
            }
        }
    }

    public PluginIdentity register(Plugin plugin) {
        Objects.requireNonNull(plugin, "Plugin cannot be null.");
        rememberInstalled(plugin);
        return registerActiveIdentity(plugin);
    }

    /** Marks the current plugin generation as teardown-only without invalidating its handles. */
    public PluginIdentity beginDisable(Plugin plugin) {
        if (plugin == null) {
            return null;
        }
        return identities.beginDisable(plugin.getClass().getClassLoader());
    }

    public void invalidate(Plugin plugin) {
        if (plugin != null) {
            identities.invalidate(plugin.getClass().getClassLoader());
        }
    }

    /** Generation-fenced invalidation for deferred Paper disable finalization. */
    public boolean invalidate(Plugin plugin, PluginIdentity expectedIdentity) {
        return plugin != null && identities.invalidate(plugin.getClass().getClassLoader(), expectedIdentity);
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
        PluginIdentity existing = find(plugin);
        if (existing != null && identities.stateOf(existing) == PluginIdentityState.DISABLING) {
            throw new SecurityException("DataProvider cannot bind a plugin while it is disabling.");
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

    @Override
    public PluginIdentityState identityState(PluginIdentity identity) {
        return identities.stateOf(identity);
    }

    private PluginIdentity registerActiveIdentity(Plugin plugin) {
        String pluginId = normalizePluginId(plugin.getName());
        ClassLoader classLoader = plugin.getClass().getClassLoader();
        PluginIdentity existing = identities.find(classLoader);
        if (existing != null && identities.stateOf(existing) == PluginIdentityState.ACTIVE) {
            return existing;
        }
        PluginIdentity identity = identities.register(pluginId, classLoader);
        if (!identity.pluginId().equals(pluginId)) {
            throw new IllegalStateException(
                    "Cannot securely distinguish Bukkit plugins '" + identity.pluginId() + "' and '"
                            + pluginId + "' because they share one class loader."
            );
        }
        return identity;
    }

    private void rememberInstalled(Plugin plugin) {
        Objects.requireNonNull(plugin, "Plugin cannot be null.");
        installedPluginIds.add(normalizePluginId(plugin.getName()));
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
