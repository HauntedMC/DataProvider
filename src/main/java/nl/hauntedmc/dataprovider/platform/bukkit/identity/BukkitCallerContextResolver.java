package nl.hauntedmc.dataprovider.platform.bukkit.identity;

import nl.hauntedmc.dataprovider.internal.identity.CallerContext;
import nl.hauntedmc.dataprovider.internal.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.internal.identity.StackCallerClassLoaderResolver;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

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
        ClassLoader callerLoader = StackCallerClassLoaderResolver.resolveExternalCaller(ownClassLoader);
        if (callerLoader == null) {
            throw new SecurityException("Could not resolve caller class loader.");
        }

        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.getClass().getClassLoader() == callerLoader) {
                return new CallerContext(plugin.getName(), callerLoader);
            }
        }

        throw new SecurityException("Caller class loader is not mapped to a Bukkit plugin.");
    }
}
