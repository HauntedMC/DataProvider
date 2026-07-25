package nl.hauntedmc.dataprovider.core.identity;

import java.util.Objects;
import java.util.UUID;

/**
 * Opaque, immutable identity issued by a platform integration for one plugin lifecycle.
 *
 * <p>The token is deliberately tied to a particular class loader and lifecycle generation.
 * A replacement plugin with the same id receives a different identity.</p>
 */
public final class PluginIdentity {

    private final String pluginId;
    private final ClassLoader classLoader;
    private final UUID token = UUID.randomUUID();

    PluginIdentity(String pluginId, ClassLoader classLoader) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("Plugin id cannot be null or blank.");
        }
        this.pluginId = pluginId.trim().toLowerCase(java.util.Locale.ROOT);
        this.classLoader = Objects.requireNonNull(classLoader, "Plugin class loader cannot be null.");
    }

    public String pluginId() {
        return pluginId;
    }

    public ClassLoader classLoader() {
        return classLoader;
    }

    UUID token() {
        return token;
    }
}
