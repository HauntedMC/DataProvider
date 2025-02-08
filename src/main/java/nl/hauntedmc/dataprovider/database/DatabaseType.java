package nl.hauntedmc.dataprovider.database;

/**
 * Supported database types.
 */
public enum DatabaseType {
    MYSQL("mysql.yml"),
    MARIADB("mariadb.yml"),
    MONGODB("mongodb.yml"),
    REDIS("redis.yml");

    private final String configFileName;

    DatabaseType(String configFileName) {
        this.configFileName = configFileName;
    }

    public String getConfigFileName() {
        return configFileName;
    }
}
