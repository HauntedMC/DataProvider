package nl.hauntedmc.dataprovider.database.document.impl.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;
import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.model.DocumentQuery;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdate;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdateOptions;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * MongoDBDataAccess:
 * Converts our custom "DocumentQuery", "DocumentUpdate", etc. into
 * MongoDB Bson objects. Also does everything asynchronously via ExecutorService.
 */
public class MongoDBDataAccess implements DocumentDataAccess {

    private final MongoClient mongoClient;
    private final String databaseName;
    private final ExecutorService executor;

    public MongoDBDataAccess(MongoClient mongoClient, String databaseName, ExecutorService executor) {
        this.mongoClient = mongoClient;
        this.databaseName = databaseName;
        this.executor = executor;
    }

    private MongoDatabase getDatabase() {
        return mongoClient.getDatabase(databaseName);
    }

    private MongoCollection<Document> getCollection(String collection) {
        return getDatabase().getCollection(collection);
    }

    // ----------------------------------------------------------------
    // Convert from user-defined DSL to Bson
    // ----------------------------------------------------------------

    private Bson toBsonQuery(DocumentQuery query) {
        // E.g. the user stored everything in a Map<String,Object>.
        // We'll convert that map to a Mongo Document, which implements Bson.
        return new Document(query.toMap());
    }

    private Bson toBsonUpdate(DocumentUpdate update) {
        // The user has a top-level map like { "$set": {...}, "$inc": {...} }.
        return new Document(update.toMap());
    }

    private UpdateOptions toMongoUpdateOptions(DocumentUpdateOptions opts) {
        UpdateOptions res = new UpdateOptions();
        res.upsert(opts.isUpsert());
        return res;
    }

    private Document toMongoDocument(Map<String, Object> doc) {
        // Convert a Map to a Document
        return new Document(doc);
    }

    // ----------------------------------------------------------------
    // 1) insertOne
    // ----------------------------------------------------------------

    @Override
    public CompletableFuture<Void> insertOne(String collection, Map<String, Object> document) {
        return CompletableFuture.runAsync(() -> {
            getCollection(collection).insertOne(toMongoDocument(document));
        }, executor);
    }

    // ----------------------------------------------------------------
    // 2) findOne
    // ----------------------------------------------------------------

    @Override
    public CompletableFuture<Map<String, Object>> findOne(String collection, DocumentQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            Document found = getCollection(collection)
                    .find(toBsonQuery(query))
                    .first();
            if (found == null) return null;
            // Convert Document back to Map
            return documentToMap(found);
        }, executor);
    }

    // ----------------------------------------------------------------
    // 3) findMany
    // ----------------------------------------------------------------

    @Override
    public CompletableFuture<List<Map<String, Object>>> findMany(String collection, DocumentQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            List<Map<String, Object>> results = new ArrayList<>();
            for (Document doc : getCollection(collection).find(toBsonQuery(query))) {
                results.add(documentToMap(doc));
            }
            return results;
        }, executor);
    }

    // ----------------------------------------------------------------
    // 4) updateOne
    // ----------------------------------------------------------------

    @Override
    public CompletableFuture<Void> updateOne(String collection,
                                             DocumentQuery query,
                                             DocumentUpdate update,
                                             DocumentUpdateOptions options) {
        return CompletableFuture.runAsync(() -> {
            getCollection(collection)
                    .updateOne(
                            toBsonQuery(query),
                            toBsonUpdate(update),
                            toMongoUpdateOptions(options)
                    );
        }, executor);
    }

    // ----------------------------------------------------------------
    // 5) updateMany
    // ----------------------------------------------------------------

    @Override
    public CompletableFuture<Void> updateMany(String collection,
                                              DocumentQuery query,
                                              DocumentUpdate update,
                                              DocumentUpdateOptions options) {
        return CompletableFuture.runAsync(() -> {
            getCollection(collection)
                    .updateMany(
                            toBsonQuery(query),
                            toBsonUpdate(update),
                            toMongoUpdateOptions(options)
                    );
        }, executor);
    }

    // ----------------------------------------------------------------
    // 6) deleteOne
    // ----------------------------------------------------------------

    @Override
    public CompletableFuture<Void> deleteOne(String collection, DocumentQuery query) {
        return CompletableFuture.runAsync(() -> {
            getCollection(collection).deleteOne(toBsonQuery(query));
        }, executor);
    }

    // ----------------------------------------------------------------
    // 7) deleteMany
    // ----------------------------------------------------------------

    @Override
    public CompletableFuture<Void> deleteMany(String collection, DocumentQuery query) {
        return CompletableFuture.runAsync(() -> {
            getCollection(collection).deleteMany(toBsonQuery(query));
        }, executor);
    }

    // ----------------------------------------------------------------
    // 8) createIndex
    // ----------------------------------------------------------------

    @Override
    public CompletableFuture<Void> createIndex(String collection,
                                               Map<String, Object> indexSpec,
                                               Map<String, Object> indexOptions) {
        return CompletableFuture.runAsync(() -> {
            // Convert indexSpec to a Document
            Document idxSpecDoc = new Document(indexSpec);

            // Convert indexOptions to IndexOptions
            IndexOptions options = mapToIndexOptions(indexOptions);

            // Use the createIndex(Bson, IndexOptions) method
            getCollection(collection).createIndex(idxSpecDoc, options);
        }, executor);
    }


    // ----------------------------------------------------------------
    // 9) dropIndex
    // ----------------------------------------------------------------

    @Override
    public CompletableFuture<Void> dropIndex(String collection, String indexName) {
        return CompletableFuture.runAsync(() -> {
            getCollection(collection).dropIndex(indexName);
        }, executor);
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private Map<String, Object> documentToMap(Document doc) {
        // Convert from org.bson.Document to a plain Map<String,Object>
        return new LinkedHashMap<>(doc);
    }

    private IndexOptions mapToIndexOptions(Map<String, Object> indexOptionsMap) {
        IndexOptions indexOptions = new IndexOptions();

        if (indexOptionsMap == null) {
            return indexOptions; // no options to set
        }

        // Just examples of setting fields — adapt to your needs:
        if (indexOptionsMap.containsKey("unique")) {
            indexOptions.unique((Boolean) indexOptionsMap.get("unique"));
        }
        if (indexOptionsMap.containsKey("background")) {
            indexOptions.background((Boolean) indexOptionsMap.get("background"));
        }
        if (indexOptionsMap.containsKey("name")) {
            indexOptions.name((String) indexOptionsMap.get("name"));
        }
        // ... set any other options similarly

        return indexOptions;
    }
}
