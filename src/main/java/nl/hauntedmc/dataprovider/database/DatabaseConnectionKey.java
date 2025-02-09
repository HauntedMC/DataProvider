package nl.hauntedmc.dataprovider.database;

import java.util.Objects;

public record DatabaseConnectionKey(String pluginName, DatabaseType type, String connectionIdentifier) {

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
    public String toString() {
        return "DatabaseConnectionKey{" +
                "pluginName='" + pluginName + '\'' +
                ", type=" + type +
                ", connectionIdentifier='" + connectionIdentifier + '\'' +
                '}';
    }
}
