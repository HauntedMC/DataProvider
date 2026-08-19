package nl.hauntedmc.dataprovider.database.relational.schema;

import java.util.concurrent.CompletableFuture;

/**
 * Defines methods for performing schema (DDL) operations.
 */
public interface SchemaManager {

    CompletableFuture<Void> createTable(TableDefinition tableDefinition);

    CompletableFuture<Void> alterTable(TableDefinition tableDefinition);

    CompletableFuture<Void> dropTable(String tableName);

    CompletableFuture<Boolean> tableExists(String tableName);

    CompletableFuture<Void> addIndex(String tableName, String column, boolean unique);

    /** Adds a non-unique index without an ambiguous boolean argument. */
    default CompletableFuture<Void> addIndex(String tableName, String column) {
        return addIndex(tableName, column, false);
    }

    /** Adds a unique index without an ambiguous boolean argument. */
    default CompletableFuture<Void> addUniqueIndex(String tableName, String column) {
        return addIndex(tableName, column, true);
    }

    CompletableFuture<Void> removeIndex(String tableName, String indexName);

    CompletableFuture<Void> addForeignKey(String table, String column, String referenceTable, String referenceColumn);

    CompletableFuture<Void> removeForeignKey(String table, String constraintName);
}
