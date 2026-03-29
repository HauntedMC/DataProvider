package nl.hauntedmc.dataprovider.platform.velocity.identity;

import com.velocitypowered.api.proxy.ProxyServer;
import nl.hauntedmc.dataprovider.internal.identity.CallerContext;
import nl.hauntedmc.dataprovider.internal.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.internal.identity.PluginCallerChainResolver;
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
        return resolveCaller(StackCallerClassLoaderResolver.resolveExternalCallerChain(ownClassLoader));
    }

    CallerContext resolveCaller(List<ClassLoader> callerChain) {
        return PluginCallerChainResolver.resolveNearestMappedCaller(
                callerChain,
                this::resolvePluginId,
                "Caller class loader is not mapped to a Velocity plugin."
        );
    }

    private String resolvePluginId(ClassLoader callerLoader) {
        return proxyServer.getPluginManager().getPlugins().stream()
                .filter(container -> container.getInstance()
                        .map(instance -> instance.getClass().getClassLoader() == callerLoader)
                        .orElse(false))
                .findFirst()
                .map(container -> container.getDescription().getId())
                .orElse(null);
    }
}
