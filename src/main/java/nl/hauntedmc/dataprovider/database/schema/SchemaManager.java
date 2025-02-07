package nl.hauntedmc.dataprovider.database.schema;

import java.util.concurrent.CompletableFuture;

public interface SchemaManager {

    /**
     * Creates a table if it does not exist.
     */
    CompletableFuture<Void> createTable(TableDefinition tableDefinition);

    /**
     * Alters an existing table.
     */
    CompletableFuture<Void> alterTable(TableDefinition tableDefinition);

    /**
     * Drops (deletes) a table.
     */
    CompletableFuture<Void> dropTable(String tableName);

    /**
     * Checks if a table exists.
     */
    CompletableFuture<Boolean> tableExists(String tableName);

    /**
     * Adds an index to a table.
     */
    CompletableFuture<Void> addIndex(String tableName, String column, boolean unique);

    /**
     * Removes an index from a table.
     */
    CompletableFuture<Void> removeIndex(String tableName, String indexName);

    /**
     * Adds a foreign key constraint between two tables.
     */
    CompletableFuture<Void> addForeignKey(String table, String column, String referenceTable, String referenceColumn);

    /**
     * Removes a foreign key constraint.
     */
    CompletableFuture<Void> removeForeignKey(String table, String constraintName);
}
