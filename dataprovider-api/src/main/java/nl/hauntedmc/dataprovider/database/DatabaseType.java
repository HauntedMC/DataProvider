package nl.hauntedmc.dataprovider.database;

import java.util.Locale;
import java.util.Optional;

/**
 * Supported database types.
 */
public enum DatabaseType {
    MYSQL("mysql.yml"),
    MONGODB("mongodb.yml"),
    REDIS("redis.yml"),
    REDIS_MESSAGING("redis_messaging.yml");

    private final String configFileName;

    DatabaseType(String configFileName) {
        this.configFileName = configFileName;
    }

    public String getConfigFileName() {
        return configFileName;
    }

    /** Stable lower-case key used by DataProvider configuration. */
    public String configKey() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a user-facing database type name, accepting case differences and hyphens for underscores.
     */
    public static Optional<DatabaseType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return Optional.of(valueOf(normalized));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
