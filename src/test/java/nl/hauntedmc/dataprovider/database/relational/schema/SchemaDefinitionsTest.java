package nl.hauntedmc.dataprovider.database.relational.schema;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDefinitionsTest {

    @Test
    void columnDefinitionExposesConfiguredValues() {
        ColumnDefinition column = new ColumnDefinition("id", "BIGINT", true, true);

        assertEquals("id", column.getName());
        assertEquals("BIGINT", column.getType());
        assertTrue(column.isNotNull());
        assertTrue(column.isAutoIncrement());
    }

    @Test
    void tableDefinitionExposesConfiguredValues() {
        List<ColumnDefinition> columns = List.of(new ColumnDefinition("id", "INT", true, true));
        TableDefinition table = new TableDefinition("players", columns, "id");

        assertEquals("players", table.getTableName());
        assertSame(columns, table.getColumns());
        assertEquals("id", table.getPrimaryKey());
    }
}
