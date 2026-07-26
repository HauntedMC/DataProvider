package nl.hauntedmc.dataprovider.core;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Internal typed representation for resolved plugin identity.
 */
record PluginId(String value) {

    private static final Pattern PLUGIN_ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,127}");

    PluginId {
        Objects.requireNonNull(value, "Plugin id cannot be null.");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Plugin id cannot be blank.");
        }
        if (!PLUGIN_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Plugin id contains unsupported characters or is too long.");
        }
        value = normalized;
    }

    static PluginId of(String value) {
        return new PluginId(value);
    }

    @Override
    public @NotNull String toString() {
        return value;
    }
}
