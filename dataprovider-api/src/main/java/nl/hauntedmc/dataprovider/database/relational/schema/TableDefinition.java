package nl.hauntedmc.dataprovider.database.relational.schema;

import java.util.List;

/**
 * Represents a table schema definition.
 */
public class TableDefinition {

    private final String tableName;
    private final List<ColumnDefinition> columns;
    private final String primaryKey;

    public TableDefinition(String tableName, List<ColumnDefinition> columns, String primaryKey) {
        this.tableName = tableName;
        this.columns = columns;
        this.primaryKey = primaryKey;
    }

    public String getTableName() {
        return tableName;
    }

    public List<ColumnDefinition> getColumns() {
        return columns;
    }

    public String getPrimaryKey() {
        return primaryKey;
    }
}
