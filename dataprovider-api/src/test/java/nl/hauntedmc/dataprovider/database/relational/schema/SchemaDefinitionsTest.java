package nl.hauntedmc.dataprovider.database.relational.schema;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void schemaManagerConveniencesRemoveAmbiguousUniqueBoolean() {
        AtomicBoolean unique = new AtomicBoolean();
        SchemaManager manager = new StubSchemaManager(unique);

        manager.addIndex("players", "name").join();
        assertFalse(unique.get());
        manager.addUniqueIndex("players", "uuid").join();
        assertTrue(unique.get());
    }

    private static final class StubSchemaManager implements SchemaManager {
        private final AtomicBoolean unique;

        private StubSchemaManager(AtomicBoolean unique) {
            this.unique = unique;
        }

        @Override public CompletableFuture<Void> createTable(TableDefinition tableDefinition) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> alterTable(TableDefinition tableDefinition) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> dropTable(String tableName) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Boolean> tableExists(String tableName) {
            return CompletableFuture.completedFuture(false);
        }
        @Override public CompletableFuture<Void> addIndex(String tableName, String column, boolean unique) {
            this.unique.set(unique);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> removeIndex(String tableName, String indexName) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> addForeignKey(
                String table,
                String column,
                String referenceTable,
                String referenceColumn
        ) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> removeForeignKey(String table, String constraintName) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
