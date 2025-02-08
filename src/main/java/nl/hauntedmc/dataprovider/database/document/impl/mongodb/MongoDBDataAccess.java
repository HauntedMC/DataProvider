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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * MongoDBDataAccess converts our custom DSL objects into MongoDB Bson objects
 * and performs asynchronous operations via an ExecutorService.
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

    private Bson toBsonQuery(DocumentQuery query) {
        return new Document(query.toMap());
    }

    private Bson toBsonUpdate(DocumentUpdate update) {
        return new Document(update.toMap());
    }

    private UpdateOptions toMongoUpdateOptions(DocumentUpdateOptions opts) {
        return new UpdateOptions().upsert(opts.isUpsert());
    }

    private Document toMongoDocument(Map<String, Object> doc) {
        return new Document(doc);
    }

    private Map<String, Object> documentToMap(Document doc) {
        return new LinkedHashMap<>(doc);
    }

    @Override
    public CompletableFuture<Void> insertOne(String collection, Map<String, Object> document) {
        return CompletableFuture.runAsync(() ->
                getCollection(collection).insertOne(toMongoDocument(document)), executor);
    }

    @Override
    public CompletableFuture<Map<String, Object>> findOne(String collection, DocumentQuery query) {
        return CompletableFuture.supplyAsync(() -> {
            Document found = getCollection(collection)
                    .find(toBsonQuery(query))
                    .first();
            return (found != null) ? documentToMap(found) : null;
        }, executor);
    }

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

    @Override
    public CompletableFuture<Void> updateOne(String collection, DocumentQuery query, DocumentUpdate update, DocumentUpdateOptions options) {
        return CompletableFuture.runAsync(() ->
                getCollection(collection)
                        .updateOne(toBsonQuery(query), toBsonUpdate(update), toMongoUpdateOptions(options)), executor);
    }

    @Override
    public CompletableFuture<Void> updateMany(String collection, DocumentQuery query, DocumentUpdate update, DocumentUpdateOptions options) {
        return CompletableFuture.runAsync(() ->
                getCollection(collection)
                        .updateMany(toBsonQuery(query), toBsonUpdate(update), toMongoUpdateOptions(options)), executor);
    }

    @Override
    public CompletableFuture<Void> deleteOne(String collection, DocumentQuery query) {
        return CompletableFuture.runAsync(() ->
                getCollection(collection).deleteOne(toBsonQuery(query)), executor);
    }

    @Override
    public CompletableFuture<Void> deleteMany(String collection, DocumentQuery query) {
        return CompletableFuture.runAsync(() ->
                getCollection(collection).deleteMany(toBsonQuery(query)), executor);
    }

    @Override
    public CompletableFuture<Void> createIndex(String collection, Map<String, Object> indexSpec, Map<String, Object> indexOptions) {
        return CompletableFuture.runAsync(() -> {
            Document idxSpecDoc = new Document(indexSpec);
            IndexOptions options = mapToIndexOptions(indexOptions);
            getCollection(collection).createIndex(idxSpecDoc, options);
        }, executor);
    }

    @Override
    public CompletableFuture<Void> dropIndex(String collection, String indexName) {
        return CompletableFuture.runAsync(() ->
                getCollection(collection).dropIndex(indexName), executor);
    }

    private IndexOptions mapToIndexOptions(Map<String, Object> indexOptionsMap) {
        IndexOptions indexOptions = new IndexOptions();
        if (indexOptionsMap != null) {
            if (indexOptionsMap.containsKey("unique")) {
                indexOptions.unique(Boolean.TRUE.equals(indexOptionsMap.get("unique")));
            }
            if (indexOptionsMap.containsKey("background")) {
                indexOptions.background(Boolean.TRUE.equals(indexOptionsMap.get("background")));
            }
            if (indexOptionsMap.containsKey("name")) {
                indexOptions.name(String.valueOf(indexOptionsMap.get("name")));
            }
        }
        return indexOptions;
    }
}
