package nl.hauntedmc.dataprovider.database;

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
}
