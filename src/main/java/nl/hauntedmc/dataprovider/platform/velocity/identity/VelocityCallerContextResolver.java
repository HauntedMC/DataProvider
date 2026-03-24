package nl.hauntedmc.dataprovider.platform.velocity.identity;

import com.velocitypowered.api.proxy.ProxyServer;
import nl.hauntedmc.dataprovider.internal.identity.CallerContext;
import nl.hauntedmc.dataprovider.internal.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.internal.identity.StackCallerClassLoaderResolver;

import java.util.List;
import java.util.Objects;

/**
 * Velocity-specific caller identity resolver.
 */
public final class VelocityCallerContextResolver implements CallerContextResolver {

    private final ProxyServer proxyServer;
    private final ClassLoader ownClassLoader;

    public VelocityCallerContextResolver(ProxyServer proxyServer, ClassLoader ownClassLoader) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "ProxyServer cannot be null.");
        this.ownClassLoader = Objects.requireNonNull(ownClassLoader, "Own class loader cannot be null.");
    }

    @Override
    public CallerContext resolveCaller() {
        List<ClassLoader> callerChain = StackCallerClassLoaderResolver.resolveExternalCallerChain(ownClassLoader);
        String resolvedPluginId = null;
        ClassLoader resolvedLoader = null;

        for (ClassLoader callerLoader : callerChain) {
            String pluginId = proxyServer.getPluginManager().getPlugins().stream()
                    .filter(container -> container.getInstance()
                            .map(instance -> instance.getClass().getClassLoader() == callerLoader)
                            .orElse(false))
                    .findFirst()
                    .map(container -> container.getDescription().getId())
                    .orElse(null);
            if (pluginId == null) {
                continue;
            }
            if (resolvedLoader == null) {
                resolvedLoader = callerLoader;
                resolvedPluginId = pluginId;
                continue;
            }
            if (resolvedLoader != callerLoader) {
                throw new SecurityException("Ambiguous caller plugin chain detected.");
            }
        }

        if (resolvedPluginId == null || resolvedLoader == null) {
            throw new SecurityException("Caller class loader is not mapped to a Velocity plugin.");
        }
        return new CallerContext(resolvedPluginId, resolvedLoader);
    }
}
