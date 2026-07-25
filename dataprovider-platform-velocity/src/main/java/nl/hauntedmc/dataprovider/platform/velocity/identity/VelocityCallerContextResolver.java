package nl.hauntedmc.dataprovider.platform.velocity.identity;

import com.velocitypowered.api.proxy.ProxyServer;
import nl.hauntedmc.dataprovider.core.identity.CallerContext;
import nl.hauntedmc.dataprovider.core.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityRegistry;

import java.util.Objects;

/**
 * Velocity-specific caller identity resolver.
 */
public final class VelocityCallerContextResolver implements CallerContextResolver {

    private final ProxyServer proxyServer;
    private final ClassLoader ownClassLoader;
    private final PluginIdentityRegistry identities = new PluginIdentityRegistry();

    public VelocityCallerContextResolver(ProxyServer proxyServer, ClassLoader ownClassLoader) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "ProxyServer cannot be null.");
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
        return identity;
    }

    @Override
    public boolean isIdentityActive(PluginIdentity identity) {
        return identities.isActive(identity);
    }
}
