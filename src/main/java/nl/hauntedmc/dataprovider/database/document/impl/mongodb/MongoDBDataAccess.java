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
import nl.hauntedmc.dataprovider.internal.concurrent.AsyncTaskSupport;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Objects;

/**
 * MongoDBDataAccess converts our custom DSL objects into MongoDB Bson objects
 * and performs asynchronous operations via an ExecutorService.
 */
public class MongoDBDataAccess implements DocumentDataAccess {

    private final MongoClient mongoClient;
    private final String databaseName;
    private final ExecutorService executor;

    public MongoDBDataAccess(MongoClient mongoClient, String databaseName, ExecutorService executor) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Mongo client cannot be null.");
        if (databaseName == null || databaseName.isBlank()) {
            throw new IllegalArgumentException("Database name cannot be null or blank.");
        }
        this.databaseName = databaseName;
        this.executor = Objects.requireNonNull(executor, "Executor cannot be null.");
    }

    private MongoDatabase getDatabase() {
        return mongoClient.getDatabase(databaseName);
    }

    private MongoCollection<Document> getCollection(String collection) {
        return getDatabase().getCollection(requireCollection(collection));
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
        Objects.requireNonNull(document, "Document cannot be null.");
        Map<String, Object> safeDocument = Map.copyOf(document);
        return AsyncTaskSupport.runAsync(executor, "mongodb.insertOne", () ->
                getCollection(collection).insertOne(toMongoDocument(safeDocument)));
    }

    @Override
    public CompletableFuture<Map<String, Object>> findOne(String collection, DocumentQuery query) {
        Objects.requireNonNull(query, "Document query cannot be null.");
        return AsyncTaskSupport.supplyAsync(executor, "mongodb.findOne", () -> {
            Document found = getCollection(collection)
                    .find(toBsonQuery(query))
                    .first();
            return (found != null) ? documentToMap(found) : null;
        });
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> findMany(String collection, DocumentQuery query) {
        Objects.requireNonNull(query, "Document query cannot be null.");
        return AsyncTaskSupport.supplyAsync(executor, "mongodb.findMany", () -> {
            List<Map<String, Object>> results = new ArrayList<>();
            for (Document doc : getCollection(collection).find(toBsonQuery(query))) {
                results.add(documentToMap(doc));
            }
            return results;
        });
    }

    @Override
    public CompletableFuture<Void> updateOne(String collection, DocumentQuery query, DocumentUpdate update, DocumentUpdateOptions options) {
        Objects.requireNonNull(query, "Document query cannot be null.");
        Objects.requireNonNull(update, "Document update cannot be null.");
        Objects.requireNonNull(options, "Document update options cannot be null.");
        return AsyncTaskSupport.runAsync(executor, "mongodb.updateOne", () ->
                getCollection(collection)
                        .updateOne(toBsonQuery(query), toBsonUpdate(update), toMongoUpdateOptions(options)));
    }

    @Override
    public CompletableFuture<Void> updateMany(String collection, DocumentQuery query, DocumentUpdate update, DocumentUpdateOptions options) {
        Objects.requireNonNull(query, "Document query cannot be null.");
        Objects.requireNonNull(update, "Document update cannot be null.");
        Objects.requireNonNull(options, "Document update options cannot be null.");
        return AsyncTaskSupport.runAsync(executor, "mongodb.updateMany", () ->
                getCollection(collection)
                        .updateMany(toBsonQuery(query), toBsonUpdate(update), toMongoUpdateOptions(options)));
    }

    @Override
    public CompletableFuture<Void> deleteOne(String collection, DocumentQuery query) {
        Objects.requireNonNull(query, "Document query cannot be null.");
        return AsyncTaskSupport.runAsync(executor, "mongodb.deleteOne", () ->
                getCollection(collection).deleteOne(toBsonQuery(query)));
    }

    @Override
    public CompletableFuture<Void> deleteMany(String collection, DocumentQuery query) {
        Objects.requireNonNull(query, "Document query cannot be null.");
        return AsyncTaskSupport.runAsync(executor, "mongodb.deleteMany", () ->
                getCollection(collection).deleteMany(toBsonQuery(query)));
    }

    @Override
    public CompletableFuture<Void> createIndex(String collection, Map<String, Object> indexSpec, Map<String, Object> indexOptions) {
        Objects.requireNonNull(indexSpec, "Index specification cannot be null.");
        Map<String, Object> safeSpec = Map.copyOf(indexSpec);
        Map<String, Object> safeOptions = indexOptions == null ? null : Map.copyOf(indexOptions);
        return AsyncTaskSupport.runAsync(executor, "mongodb.createIndex", () -> {
            Document idxSpecDoc = new Document(safeSpec);
            IndexOptions options = mapToIndexOptions(safeOptions);
            getCollection(collection).createIndex(idxSpecDoc, options);
        });
    }

    @Override
    public CompletableFuture<Void> dropIndex(String collection, String indexName) {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("Index name cannot be null or blank.");
        }
        return AsyncTaskSupport.runAsync(executor, "mongodb.dropIndex", () ->
                getCollection(collection).dropIndex(indexName));
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
            if (indexOptionsMap.containsKey("sparse")) {
                indexOptions.sparse(Boolean.TRUE.equals(indexOptionsMap.get("sparse")));
            }
            if (indexOptionsMap.containsKey("expireAfterSeconds")) {
                Object expireAfter = indexOptionsMap.get("expireAfterSeconds");
                if (expireAfter instanceof Number number) {
                    long seconds = number.longValue();
                    if (seconds < 0) {
                        throw new IllegalArgumentException("Index option 'expireAfterSeconds' cannot be negative.");
                    }
                    indexOptions.expireAfter(seconds, TimeUnit.SECONDS);
                } else {
                    throw new IllegalArgumentException("Index option 'expireAfterSeconds' must be numeric.");
                }
            }
            if (indexOptionsMap.containsKey("partialFilterExpression")) {
                Object partialFilterExpression = indexOptionsMap.get("partialFilterExpression");
                if (partialFilterExpression instanceof Map<?, ?> mapValue) {
                    Document partialDocument = new Document();
                    for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                        partialDocument.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    indexOptions.partialFilterExpression(partialDocument);
                } else if (partialFilterExpression instanceof Document documentValue) {
                    indexOptions.partialFilterExpression(documentValue);
                } else {
                    throw new IllegalArgumentException(
                            "Index option 'partialFilterExpression' must be a map or BSON document."
                    );
                }
            }
        }
        return indexOptions;
    }

    private static String requireCollection(String collection) {
        if (collection == null || collection.isBlank()) {
            throw new IllegalArgumentException("Collection name cannot be null or blank.");
        }
        if (collection.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Collection name cannot contain null characters.");
        }
        return collection;
    }
}
