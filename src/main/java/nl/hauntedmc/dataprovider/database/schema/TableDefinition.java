package nl.hauntedmc.dataprovider.database.schema;

import java.util.ArrayList;
import java.util.List;

public class TableDefinition {
    private final String tableName;
    private final List<ColumnDefinition> columns = new ArrayList<>();
    private String primaryKey;

    public TableDefinition(String tableName) {
        this.tableName = tableName;
    }

    public TableDefinition addColumn(ColumnDefinition column) {
        columns.add(column);
        return this;
    }

    public TableDefinition primaryKey(String primaryKey) {
        this.primaryKey = primaryKey;
        return this;
    }

    public String getTableName() { return tableName; }
    public List<ColumnDefinition> getColumns() { return columns; }
    public String getPrimaryKey() { return primaryKey; }
}
