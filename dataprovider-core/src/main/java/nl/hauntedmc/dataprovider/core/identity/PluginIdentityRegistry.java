package nl.hauntedmc.dataprovider.core.identity;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe lifecycle registry owned by one platform integration. */
public final class PluginIdentityRegistry {

    private final Map<ClassLoader, PluginIdentity> identitiesByLoader = new ConcurrentHashMap<>();
    private final Map<UUID, PluginIdentity> identitiesByToken = new ConcurrentHashMap<>();

    public PluginIdentity register(String pluginId, ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "Plugin class loader cannot be null.");
        PluginIdentity identity = new PluginIdentity(pluginId, classLoader);
        // Replacing either index invalidates the previous lifecycle generation.
        PluginIdentity previous = identitiesByLoader.put(classLoader, identity);
        if (previous != null) {
            identitiesByToken.remove(previous.token(), previous);
        }
        identitiesByToken.put(identity.token(), identity);
        return identity;
    }

    public PluginIdentity find(ClassLoader classLoader) {
        return identitiesByLoader.get(classLoader);
    }

    public void invalidate(ClassLoader classLoader) {
        PluginIdentity identity = identitiesByLoader.remove(classLoader);
        if (identity != null) {
            identitiesByToken.remove(identity.token(), identity);
        }
    }

    public void invalidateAll() {
        identitiesByLoader.clear();
        identitiesByToken.clear();
    }

    public boolean isActive(PluginIdentity identity) {
        return identity != null
                && identitiesByLoader.get(identity.classLoader()) == identity
                && identitiesByToken.get(identity.token()) == identity;
    }

    public boolean isKnownPlugin(String pluginId) {
        if (pluginId == null) {
            return false;
        }
        String normalized = pluginId.trim().toLowerCase(java.util.Locale.ROOT);
        return identitiesByLoader.values().stream().anyMatch(identity -> identity.pluginId().equals(normalized));
    }
}
