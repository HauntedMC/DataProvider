package nl.hauntedmc.dataprovider.registry;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import java.util.Objects;

public class DatabaseConnectionKey {
    private final String pluginName;
    private final DatabaseType type;
    private final String connectionIdentifier;

    public DatabaseConnectionKey(String pluginName, DatabaseType type, String connectionIdentifier) {
        this.pluginName = pluginName;
        this.type = type;
        this.connectionIdentifier = connectionIdentifier;
    }

    public String getPluginName() {
        return pluginName;
    }

    public DatabaseType getType() {
        return type;
    }

    public String getConnectionIdentifier() {
        return connectionIdentifier;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DatabaseConnectionKey)) return false;
        DatabaseConnectionKey that = (DatabaseConnectionKey) o;
        return Objects.equals(pluginName, that.pluginName) &&
                type == that.type &&
                Objects.equals(connectionIdentifier, that.connectionIdentifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pluginName, type, connectionIdentifier);
    }

    @Override
    public String toString() {
        return "DatabaseConnectionKey{" +
                "pluginName='" + pluginName + '\'' +
                ", type=" + type +
                ", connectionIdentifier='" + connectionIdentifier + '\'' +
                '}';
    }
}
