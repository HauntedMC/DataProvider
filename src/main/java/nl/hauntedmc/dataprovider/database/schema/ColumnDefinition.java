package nl.hauntedmc.dataprovider.database.schema;

public class ColumnDefinition {
    private final String name;
    private final String type;
    private boolean notNull;
    private boolean autoIncrement;
    private String defaultValue;

    public ColumnDefinition(String name, String type) {
        this.name = name;
        this.type = type;
    }

    public ColumnDefinition notNull() {
        this.notNull = true;
        return this;
    }

    public ColumnDefinition autoIncrement() {
        this.autoIncrement = true;
        return this;
    }

    public ColumnDefinition defaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    public String getName() { return name; }
    public String getType() { return type; }
    public boolean isNotNull() { return notNull; }
    public boolean isAutoIncrement() { return autoIncrement; }
    public String getDefaultValue() { return defaultValue; }
}
