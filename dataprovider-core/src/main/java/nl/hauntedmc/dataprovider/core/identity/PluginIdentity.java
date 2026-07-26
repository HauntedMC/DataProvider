package nl.hauntedmc.dataprovider.core.identity;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Opaque, immutable identity issued by a platform integration for one plugin lifecycle.
 *
 * <p>The token is deliberately tied to a particular class loader and lifecycle generation.
 * A replacement plugin with the same id receives a different identity.</p>
 */
public final class PluginIdentity {

    private static final Pattern PLUGIN_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,127}");

    private final String pluginId;
    private final ClassLoader classLoader;
    private final UUID token = UUID.randomUUID();

    PluginIdentity(String pluginId, ClassLoader classLoader) {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("Plugin id cannot be null or blank.");
        }
        String normalized = pluginId.trim().toLowerCase(java.util.Locale.ROOT);
        if (!PLUGIN_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Plugin id contains unsupported characters or is too long.");
        }
        this.pluginId = normalized;
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
