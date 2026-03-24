package nl.hauntedmc.dataprovider.internal.identity;

import java.util.Objects;

/**
 * Immutable caller identity resolved by the active platform.
 *
 * @param pluginId    platform plugin identifier/name
 * @param classLoader caller class loader
 */
public record CallerContext(String pluginId, ClassLoader classLoader) {

    public CallerContext {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("Caller plugin id cannot be null or blank.");
        }
        Objects.requireNonNull(classLoader, "Caller class loader cannot be null.");
    }
}
