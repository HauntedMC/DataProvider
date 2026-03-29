package nl.hauntedmc.dataprovider.platform.bukkit.identity;

import nl.hauntedmc.dataprovider.internal.identity.CallerContext;
import nl.hauntedmc.dataprovider.internal.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.internal.identity.PluginCallerChainResolver;
import nl.hauntedmc.dataprovider.internal.identity.StackCallerClassLoaderResolver;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Objects;

/**
 * Bukkit-specific caller identity resolver.
 */
public final class BukkitCallerContextResolver implements CallerContextResolver {

    private final ClassLoader ownClassLoader;

    public BukkitCallerContextResolver(ClassLoader ownClassLoader) {
        this.ownClassLoader = Objects.requireNonNull(ownClassLoader, "Own class loader cannot be null.");
    }

    @Override
    public CallerContext resolveCaller() {
        return resolveCaller(StackCallerClassLoaderResolver.resolveExternalCallerChain(ownClassLoader));
    }

    CallerContext resolveCaller(List<ClassLoader> callerChain) {
        return PluginCallerChainResolver.resolveNearestMappedCaller(
                callerChain,
                this::resolvePluginName,
                "Caller class loader is not mapped to a Bukkit plugin."
        );
    }

    private String resolvePluginName(ClassLoader callerLoader) {
        Plugin plugin = findPluginByClassLoader(callerLoader);
        return plugin == null ? null : plugin.getName();
    }

    private static Plugin findPluginByClassLoader(ClassLoader callerLoader) {
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.getClass().getClassLoader() == callerLoader) {
                return plugin;
            }
        }
        return null;
    }
}
