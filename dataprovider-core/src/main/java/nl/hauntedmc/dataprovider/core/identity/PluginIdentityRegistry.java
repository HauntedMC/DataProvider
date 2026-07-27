package nl.hauntedmc.dataprovider.core.identity;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe lifecycle registry owned by one platform integration. */
public final class PluginIdentityRegistry {

    private final Map<ClassLoader, PluginIdentity> identitiesByLoader = new ConcurrentHashMap<>();
    private final Map<UUID, PluginIdentity> identitiesByToken = new ConcurrentHashMap<>();
    private final Map<UUID, PluginIdentityState> statesByToken = new ConcurrentHashMap<>();

    public synchronized PluginIdentity register(String pluginId, ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "Plugin class loader cannot be null.");
        PluginIdentity identity = new PluginIdentity(pluginId, classLoader);
        PluginIdentity mapped = identitiesByLoader.get(classLoader);
        if (mapped != null && !mapped.pluginId().equals(identity.pluginId())) {
            throw new IllegalStateException(
                    "Cannot securely distinguish plugins '" + mapped.pluginId() + "' and '"
                            + identity.pluginId() + "' because they share one class loader."
            );
        }
        // Replacing either index invalidates the previous lifecycle generation.
        PluginIdentity previous = identitiesByLoader.put(classLoader, identity);
        if (previous != null) {
            identitiesByToken.remove(previous.token(), previous);
            statesByToken.remove(previous.token());
        }
        identitiesByToken.put(identity.token(), identity);
        statesByToken.put(identity.token(), PluginIdentityState.ACTIVE);
        return identity;
    }

    public synchronized PluginIdentity find(ClassLoader classLoader) {
        return identitiesByLoader.get(classLoader);
    }

    /** Transitions the current class-loader generation into teardown mode. */
    public synchronized PluginIdentity beginDisable(ClassLoader classLoader) {
        PluginIdentity identity = identitiesByLoader.get(classLoader);
        if (identity == null || identitiesByToken.get(identity.token()) != identity) {
            return null;
        }
        statesByToken.computeIfPresent(identity.token(), (ignored, state) ->
                state == PluginIdentityState.ACTIVE ? PluginIdentityState.DISABLING : state);
        return identity;
    }

    public synchronized void invalidate(ClassLoader classLoader) {
        invalidate(classLoader, null);
    }

    /**
     * Invalidates a class-loader generation only when it still matches the expected identity.
     * This fences a delayed disable callback from invalidating a newly enabled replacement.
     */
    public synchronized boolean invalidate(ClassLoader classLoader, PluginIdentity expectedIdentity) {
        PluginIdentity identity = identitiesByLoader.get(classLoader);
        if (identity == null || expectedIdentity != null && identity != expectedIdentity) {
            return false;
        }
        identitiesByLoader.remove(classLoader, identity);
        identitiesByToken.remove(identity.token(), identity);
        statesByToken.remove(identity.token());
        return true;
    }

    public synchronized void invalidateAll() {
        identitiesByLoader.clear();
        identitiesByToken.clear();
        statesByToken.clear();
    }

    public synchronized PluginIdentityState stateOf(PluginIdentity identity) {
        if (identity == null
                || identitiesByLoader.get(identity.classLoader()) != identity
                || identitiesByToken.get(identity.token()) != identity) {
            return PluginIdentityState.INACTIVE;
        }
        return statesByToken.getOrDefault(identity.token(), PluginIdentityState.INACTIVE);
    }

    public synchronized boolean isActive(PluginIdentity identity) {
        return stateOf(identity) == PluginIdentityState.ACTIVE;
    }

    public synchronized boolean isKnownPlugin(String pluginId) {
        if (pluginId == null) {
            return false;
        }
        String normalized = pluginId.trim().toLowerCase(java.util.Locale.ROOT);
        return identitiesByLoader.values().stream().anyMatch(identity -> identity.pluginId().equals(normalized));
    }
}
