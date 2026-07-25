package nl.hauntedmc.dataprovider.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record DatabaseConnectionKey(String pluginName, DatabaseType type, String connectionIdentifier) {

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
                "pluginName='" + pluginName + '\'' +
                ", type=" + type +
                ", connectionIdentifier='" + connectionIdentifier + '\'' +
                '}';
    }
}
