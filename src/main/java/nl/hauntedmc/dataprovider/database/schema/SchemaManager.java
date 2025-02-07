package nl.hauntedmc.dataprovider.database.schema;

import java.util.concurrent.CompletableFuture;

public interface SchemaManager {
    CompletableFuture<Void> createTable(TableDefinition tableDefinition);
    CompletableFuture<Void> updateTable(TableDefinition tableDefinition);
}
