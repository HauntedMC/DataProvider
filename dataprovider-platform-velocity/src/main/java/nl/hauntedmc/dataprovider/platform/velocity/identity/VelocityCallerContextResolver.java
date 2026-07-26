package nl.hauntedmc.dataprovider.platform.velocity.identity;

import com.velocitypowered.api.proxy.ProxyServer;
import nl.hauntedmc.dataprovider.core.identity.CallerContext;
import nl.hauntedmc.dataprovider.core.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.core.identity.PluginCallerChainResolver;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityRegistry;
import nl.hauntedmc.dataprovider.core.identity.StackCallerClassLoaderResolver;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Velocity-specific caller identity resolver.
 */
public final class VelocityCallerContextResolver implements CallerContextResolver {

    private final ProxyServer proxyServer;
    private final ClassLoader ownClassLoader;
    private final PluginIdentityRegistry identities = new PluginIdentityRegistry();
    private final Supplier<List<ClassLoader>> callerChain;

    public VelocityCallerContextResolver(ProxyServer proxyServer, ClassLoader ownClassLoader) {
        this(
                proxyServer,
                ownClassLoader,
                () -> StackCallerClassLoaderResolver.resolveExternalCallerChain(ownClassLoader)
        );
    }

    VelocityCallerContextResolver(
            ProxyServer proxyServer,
            ClassLoader ownClassLoader,
            Supplier<List<ClassLoader>> callerChain
    ) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "ProxyServer cannot be null.");
        this.ownClassLoader = Objects.requireNonNull(ownClassLoader, "Own class loader cannot be null.");
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
                "Could not resolve the calling Velocity plugin."
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

    /** Called while Velocity initializes the provider, before plugin work is scheduled. */
    public void synchronizePlugins() {
        proxyServer.getPluginManager().getPlugins().forEach(container -> container.getInstance().ifPresent(instance ->
                identities.register(container.getDescription().getId(), instance.getClass().getClassLoader())
        ));
    }

    public void invalidateAll() {
        identities.invalidateAll();
    }

    @Override
    public PluginIdentity issueIdentity(Object platformPlugin) {
        Objects.requireNonNull(platformPlugin, "Platform plugin cannot be null.");
        PluginIdentity identity = identities.find(platformPlugin.getClass().getClassLoader());
        if (identity == null) {
            throw new SecurityException("Velocity plugin is not active in DataProvider's identity registry.");
        }
        CallerContext caller = resolveCaller();
        if (!identity.pluginId().equals(caller.pluginId()) || identity.classLoader() != caller.classLoader()) {
            throw new SecurityException("A Velocity plugin can bind DataProvider only to its own plugin instance.");
        }
        return identity;
    }

    @Override
    public boolean isIdentityActive(PluginIdentity identity) {
        return identities.isActive(identity);
    }
}
