package nl.hauntedmc.dataprovider.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.regex.Pattern;

public record DatabaseConnectionKey(String pluginName, DatabaseType type, String connectionIdentifier) {

    private static final Pattern PLUGIN_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Pattern CONNECTION_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    public DatabaseConnectionKey {
        pluginName = normalize(pluginName, PLUGIN_PATTERN, "Plugin name");
        type = Objects.requireNonNull(type, "Database type cannot be null.");
        connectionIdentifier = normalize(
                connectionIdentifier,
                CONNECTION_PATTERN,
                "Connection identifier"
        );
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof DatabaseConnectionKey(String nameOther, DatabaseType typeOther, String identifierOther))) return false;
        return Objects.equals(pluginName, nameOther) &&
                type == typeOther &&
                Objects.equals(connectionIdentifier, identifierOther);
    }

    @Override
    public @NotNull String toString() {
        return "DatabaseConnectionKey{" +
                "type=" + type +
                ", identifiers=<redacted>" +
                '}';
    }

    private static String normalize(String value, Pattern pattern, String field) {
        Objects.requireNonNull(value, field + " cannot be null.");
        String normalized = value.trim();
        if (!pattern.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters or has an invalid length.");
        }
        return normalized;
    }
}
