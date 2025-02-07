package nl.hauntedmc.dataprovider.database.schema;

/**
 * Represents a single column in a table.
 */
public class ColumnDefinition {

    private final String name;
    private final String type;
    private final boolean notNull;
    private final boolean autoIncrement;

    public ColumnDefinition(String name, String type, boolean notNull, boolean autoIncrement) {
        this.name = name;
        this.type = type;
        this.notNull = notNull;
        this.autoIncrement = autoIncrement;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public boolean isNotNull() {
        return notNull;
    }

    public boolean isAutoIncrement() {
        return autoIncrement;
    }
}
