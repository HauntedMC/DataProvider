package nl.hauntedmc.dataprovider.core.database.document.impl.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.UpdateOptions;
import nl.hauntedmc.dataprovider.core.concurrent.AsyncTaskSupport;
import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.model.DocumentQuery;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdate;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdateOptions;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Binary;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** MongoDB data access routed through a runtime-managed execution scope. */
public class MongoDBDataAccess implements DocumentDataAccess {

    private final MongoClient mongoClient;
    private final String databaseName;
    private final Executor executor;

    public MongoDBDataAccess(MongoClient mongoClient, String databaseName, Executor executor) {
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
        return toMongoDocument(query.toMap());
    }

    private Bson toBsonUpdate(DocumentUpdate update) {
        return toMongoDocument(update.toMap());
    }

    private UpdateOptions toMongoUpdateOptions(DocumentUpdateOptions options) {
        return new UpdateOptions().upsert(options.isUpsert());
    }

    private Document toMongoDocument(Map<?, ?> document) {
        return copyDocument(document, "document");
    }

    private Map<String, Object> documentToMap(Document document) {
        return new LinkedHashMap<>(copyDocument(document, "document"));
    }

    @Override
    public CompletableFuture<Void> insertOne(String collection, Map<String, Object> document) {
        Objects.requireNonNull(document, "Document cannot be null.");
        Document safeDocument = toMongoDocument(document);
        return AsyncTaskSupport.runAsync(executor, "mongodb.insertOne",
                () -> getCollection(collection).insertOne(safeDocument));
    }

    @Override
    public CompletableFuture<Map<String, Object>> findOne(String collection, DocumentQuery query) {
        Objects.requireNonNull(query, "Document query cannot be null.");
        return AsyncTaskSupport.supplyAsync(executor, "mongodb.findOne", () -> {
            Document found = getCollection(collection).find(toBsonQuery(query)).first();
            return found == null ? null : documentToMap(found);
        });
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> findMany(String collection, DocumentQuery query) {
        Objects.requireNonNull(query, "Document query cannot be null.");
        return AsyncTaskSupport.supplyAsync(executor, "mongodb.findMany", () -> {
            List<Map<String, Object>> results = new ArrayList<>();
            for (Document document : getCollection(collection).find(toBsonQuery(query))) {
                results.add(documentToMap(document));
            }
            return results;
        });
    }

    @Override
    public CompletableFuture<Void> updateOne(
            String collection,
            DocumentQuery query,
            DocumentUpdate update,
            DocumentUpdateOptions options
    ) {
        Objects.requireNonNull(query, "Document query cannot be null.");
        Objects.requireNonNull(update, "Document update cannot be null.");
        Objects.requireNonNull(options, "Document update options cannot be null.");
        return AsyncTaskSupport.runAsync(executor, "mongodb.updateOne", () -> getCollection(collection)
                .updateOne(toBsonQuery(query), toBsonUpdate(update), toMongoUpdateOptions(options)));
    }

    @Override
    public CompletableFuture<Void> updateMany(
            String collection,
            DocumentQuery query,
            DocumentUpdate update,
            DocumentUpdateOptions options
    ) {
        Objects.requireNonNull(query, "Document query cannot be null.");
        Objects.requireNonNull(update, "Document update cannot be null.");
        Objects.requireNonNull(options, "Document update options cannot be null.");
        return AsyncTaskSupport.runAsync(executor, "mongodb.updateMany", () -> getCollection(collection)
                .updateMany(toBsonQuery(query), toBsonUpdate(update), toMongoUpdateOptions(options)));
    }

    @Override
    public CompletableFuture<Void> deleteOne(String collection, DocumentQuery query) {
        Objects.requireNonNull(query, "Document query cannot be null.");
        return AsyncTaskSupport.runAsync(executor, "mongodb.deleteOne",
                () -> getCollection(collection).deleteOne(toBsonQuery(query)));
    }

    @Override
    public CompletableFuture<Void> deleteMany(String collection, DocumentQuery query) {
        Objects.requireNonNull(query, "Document query cannot be null.");
        return AsyncTaskSupport.runAsync(executor, "mongodb.deleteMany",
                () -> getCollection(collection).deleteMany(toBsonQuery(query)));
    }

    @Override
    public CompletableFuture<Void> createIndex(
            String collection,
            Map<String, Object> indexSpec,
            Map<String, Object> indexOptions
    ) {
        Objects.requireNonNull(indexSpec, "Index specification cannot be null.");
        Document safeSpec = toMongoDocument(indexSpec);
        Document safeOptions = indexOptions == null ? null : toMongoDocument(indexOptions);
        return AsyncTaskSupport.runAsync(executor, "mongodb.createIndex",
                () -> getCollection(collection).createIndex(safeSpec, mapToIndexOptions(safeOptions)));
    }

    @Override
    public CompletableFuture<Void> dropIndex(String collection, String indexName) {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("Index name cannot be null or blank.");
        }
        return AsyncTaskSupport.runAsync(executor, "mongodb.dropIndex",
                () -> getCollection(collection).dropIndex(indexName));
    }

    private IndexOptions mapToIndexOptions(Map<String, Object> optionsMap) {
        IndexOptions options = new IndexOptions();
        if (optionsMap == null) {
            return options;
        }
        if (optionsMap.containsKey("unique")) {
            options.unique(Boolean.TRUE.equals(optionsMap.get("unique")));
        }
        if (optionsMap.containsKey("background")) {
            options.background(Boolean.TRUE.equals(optionsMap.get("background")));
        }
        if (optionsMap.containsKey("name")) {
            options.name(String.valueOf(optionsMap.get("name")));
        }
        if (optionsMap.containsKey("sparse")) {
            options.sparse(Boolean.TRUE.equals(optionsMap.get("sparse")));
        }
        if (optionsMap.containsKey("expireAfterSeconds")) {
            Object expireAfter = optionsMap.get("expireAfterSeconds");
            if (!(expireAfter instanceof Number number) || number.longValue() < 0) {
                throw new IllegalArgumentException("Index option 'expireAfterSeconds' must be a non-negative number.");
            }
            options.expireAfter(number.longValue(), TimeUnit.SECONDS);
        }
        if (optionsMap.containsKey("partialFilterExpression")) {
            Object partial = optionsMap.get("partialFilterExpression");
            if (partial instanceof Map<?, ?> map) {
                options.partialFilterExpression(toMongoDocument(map));
            } else if (partial instanceof Document document) {
                options.partialFilterExpression(document);
            } else {
                throw new IllegalArgumentException(
                        "Index option 'partialFilterExpression' must be a map or BSON document.");
            }
        }
        return options;
    }

    private static Document copyDocument(Map<?, ?> source, String path) {
        Document copy = new Document();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String fieldName)) {
                throw new IllegalArgumentException("BSON document field names must be strings at " + path + ".");
            }
            if (fieldName.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("BSON field names cannot contain null characters at " + path + ".");
            }
            copy.put(fieldName, copyBsonValue(entry.getValue(), path + "." + fieldName));
        }
        return copy;
    }

    private static Object copyBsonValue(Object value, String path) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Integer
                || value instanceof Long || value instanceof Double || value instanceof Decimal128
                || value instanceof ObjectId || value instanceof UUID || value instanceof Pattern) {
            return value;
        }
        if (value instanceof Date date) {
            return new Date(date.getTime());
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof Binary binary) {
            return new Binary(binary.getType(), binary.getData());
        }
        if (value instanceof Map<?, ?> map) {
            return copyDocument(map, path);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (int index = 0; index < list.size(); index++) {
                copy.add(copyBsonValue(list.get(index), path + "[" + index + "]"));
            }
            return copy;
        }
        throw new IllegalArgumentException(
                "Unsupported BSON value at " + path + ": " + value.getClass().getName() + ".");
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
