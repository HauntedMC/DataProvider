package nl.hauntedmc.dataprovider.database;

public enum DatabaseType {
    MYSQL("mysql.yml"),
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
