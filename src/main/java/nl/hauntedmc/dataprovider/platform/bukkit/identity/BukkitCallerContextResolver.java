package nl.hauntedmc.dataprovider.platform.bukkit.identity;

import nl.hauntedmc.dataprovider.internal.identity.CallerContext;
import nl.hauntedmc.dataprovider.internal.identity.CallerContextResolver;
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
        List<ClassLoader> callerChain = StackCallerClassLoaderResolver.resolveExternalCallerChain(ownClassLoader);
        Plugin resolvedPlugin = null;
        ClassLoader resolvedLoader = null;

        for (ClassLoader callerLoader : callerChain) {
            Plugin plugin = findPluginByClassLoader(callerLoader);
            if (plugin == null) {
                continue;
            }
            if (resolvedPlugin == null) {
                resolvedPlugin = plugin;
                resolvedLoader = callerLoader;
                continue;
            }
            if (callerLoader != resolvedLoader) {
                throw new SecurityException("Ambiguous caller plugin chain detected.");
            }
        }

        if (resolvedPlugin == null || resolvedLoader == null) {
            throw new SecurityException("Caller class loader is not mapped to a Bukkit plugin.");
        }
        return new CallerContext(resolvedPlugin.getName(), resolvedLoader);
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
