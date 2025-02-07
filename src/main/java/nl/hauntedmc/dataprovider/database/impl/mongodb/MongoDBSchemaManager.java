package nl.hauntedmc.dataprovider.database.impl.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import nl.hauntedmc.dataprovider.database.schema.SchemaManager;
import nl.hauntedmc.dataprovider.database.schema.TableDefinition;
import org.bson.Document;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class MongoDBSchemaManager implements SchemaManager {

    private final MongoClient mongoClient;
    private final String databaseName;
    private final ExecutorService executor;

    public MongoDBSchemaManager(MongoClient mongoClient, String databaseName, ExecutorService executor) {
        this.mongoClient = mongoClient;
        this.databaseName = databaseName;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Void> createTable(TableDefinition tableDefinition) {
        // In Mongo, "createTable" would roughly equate to "createCollection".
        // There's no real concept of columns, but we'll just create the collection if needed.
        return CompletableFuture.runAsync(() -> {
            MongoDatabase db = mongoClient.getDatabase(databaseName);
            // If collection already exists, MongoDB won't re-create it.
            // The driver doesn't have a direct "createCollectionIfNotExists" method.
            // You might need to listCollections or just rely on Mongo's lazy creation.
            db.createCollection(tableDefinition.getTableName());
        }, executor);
    }

    @Override
    public CompletableFuture<Void> alterTable(TableDefinition tableDefinition) {
        // Mongo does not have an "alter table" concept.
        // Typically you just store documents of new shape.
        // We can no-op here or handle some logic for migrations.
        return CompletableFuture.runAsync(() -> {
            // No-op for Mongo
        }, executor);
    }

    @Override
    public CompletableFuture<Void> dropTable(String tableName) {
        // In Mongo, dropping a "table" is dropping a "collection".
        return CompletableFuture.runAsync(() -> {
            MongoDatabase db = mongoClient.getDatabase(databaseName);
            db.getCollection(tableName).drop();
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> tableExists(String tableName) {
        return CompletableFuture.supplyAsync(() -> {
            MongoDatabase db = mongoClient.getDatabase(databaseName);
            for (String name : db.listCollectionNames()) {
                if (name.equalsIgnoreCase(tableName)) {
                    return true;
                }
            }
            return false;
        }, executor);
    }

    @Override
    public CompletableFuture<Void> addIndex(String tableName, String column, boolean unique) {
        return CompletableFuture.runAsync(() -> {
            MongoCollection<Document> collection = mongoClient.getDatabase(databaseName).getCollection(tableName);

            // Create the index keys
            Document indexKeys = new Document(column, 1); // 1 = ascending

            // Create the IndexOptions and set "unique" if desired
            IndexOptions options = new IndexOptions().unique(unique);

            // Now call createIndex with the keys and the IndexOptions object
            collection.createIndex(indexKeys, options);
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeIndex(String tableName, String indexName) {
        // If you know the index name, you can drop it.
        return CompletableFuture.runAsync(() -> {
            MongoCollection<Document> collection = mongoClient.getDatabase(databaseName).getCollection(tableName);
            collection.dropIndex(indexName);
        }, executor);
    }

    @Override
    public CompletableFuture<Void> addForeignKey(String table, String column, String referenceTable, String referenceColumn) {
        // Mongo does not have foreign key constraints in the same sense as SQL.
        // This is a placeholder.
        return CompletableFuture.runAsync(() -> {
            // No-op or implement your own reference logic
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeForeignKey(String table, String constraintName) {
        // Again, no concept of foreign keys in standard Mongo.
        return CompletableFuture.runAsync(() -> {
            // No-op
        }, executor);
    }
}
