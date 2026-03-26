package nl.hauntedmc.dataprovider.internal;

import java.util.Objects;

/**
 * Internal typed representation for resolved plugin identity.
 */
record PluginId(String value) {

    PluginId {
        Objects.requireNonNull(value, "Plugin id cannot be null.");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Plugin id cannot be blank.");
        }
        value = normalized;
    }

    static PluginId of(String value) {
        return new PluginId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
