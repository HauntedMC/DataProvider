package nl.hauntedmc.dataprovider.database.impl.mongodb;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import nl.hauntedmc.dataprovider.database.access.DataAccess;
import nl.hauntedmc.dataprovider.database.access.TransactionCallback;
import org.bson.Document;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * MongoDB implementation of DataAccess.
 *
 * NOTE: The method signatures come from the SQL-based interface.
 *       In MongoDB, there's no direct concept of "executeUpdate(query, ...)" as with SQL.
 *       You should adapt these methods to your real usage, or create a more Mongo-friendly interface.
 */
public class MongoDBDataAccess implements DataAccess {

    private final MongoClient mongoClient;
    private final String databaseName;
    private final ExecutorService executor;

    public MongoDBDataAccess(MongoClient mongoClient, String databaseName, ExecutorService executor) {
        this.mongoClient = mongoClient;
        this.databaseName = databaseName;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Void> executeUpdate(String query, Object... params) {
        // For Mongo, we might interpret `query` as the collection name,
        // then 'params' might contain the filter and the document to update/insert.
        // This is entirely up to you. Here's a naive example of an insert:

        return CompletableFuture.runAsync(() -> {
            // Example usage: query = "myCollection"
            // params[0] = Map<String, Object> (the document to insert)
            if (params.length < 1) {
                throw new IllegalArgumentException("No document provided for insert.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> docMap = (Map<String, Object>) params[0];

            MongoCollection<Document> collection = getDatabase().getCollection(query);
            Document doc = new Document(docMap);
            collection.insertOne(doc);
        }, executor);
    }

    @Override
    public CompletableFuture<Map<String, Object>> queryForSingle(String query, Object... params) {
        // For Mongo, we might interpret 'query' as the collection name,
        // and params[0] as the filter Document.
        return CompletableFuture.supplyAsync(() -> {
            if (params.length < 1) {
                throw new IllegalArgumentException("No filter provided for query.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> filterMap = (Map<String, Object>) params[0];
            Document filterDoc = new Document(filterMap);

            MongoCollection<Document> collection = getDatabase().getCollection(query);
            Document found = collection.find(filterDoc).first();
            return found != null ? documentToMap(found) : null;
        }, executor);
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> queryForList(String query, Object... params) {
        // Similar to queryForSingle, but we return multiple documents
        return CompletableFuture.supplyAsync(() -> {
            if (params.length < 1) {
                throw new IllegalArgumentException("No filter provided for queryForList.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> filterMap = (Map<String, Object>) params[0];
            Document filterDoc = new Document(filterMap);

            MongoCollection<Document> collection = getDatabase().getCollection(query);
            List<Map<String, Object>> results = new ArrayList<>();
            for (Document doc : collection.find(filterDoc)) {
                results.add(documentToMap(doc));
            }
            return results;
        }, executor);
    }

    @Override
    public CompletableFuture<Object> queryForSingleValue(String query, Object... params) {
        // Possibly for something like counting documents, or returning a single field
        return CompletableFuture.supplyAsync(() -> {
            if (params.length < 1) {
                throw new IllegalArgumentException("No filter provided for queryForSingleValue.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> filterMap = (Map<String, Object>) params[0];
            Document filterDoc = new Document(filterMap);

            MongoCollection<Document> collection = getDatabase().getCollection(query);

            // Example: let's do a count
            long count = collection.countDocuments(filterDoc);
            return count;
        }, executor);
    }

    @Override
    public CompletableFuture<Void> executeBatchUpdate(String query, List<Object[]> batchParams) {
        // For a batch insert, for example:
        return CompletableFuture.runAsync(() -> {
            MongoCollection<Document> collection = getDatabase().getCollection(query);
            List<Document> docs = new ArrayList<>();

            for (Object[] param : batchParams) {
                // Each param might be a single Map or some other structure
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) param[0];
                docs.add(new Document(map));
            }
            collection.insertMany(docs);
        }, executor);
    }

    @Override
    public <T> CompletableFuture<T> executeTransactionally(TransactionCallback<T> callback) {
        // MongoDB supports multi-document ACID transactions, but only on replica sets or sharded clusters.
        // You need a session for that. For simplicity, we just run the callback in the same thread
        // without an actual transaction. Adjust as needed for a real transaction scenario.

        return CompletableFuture.supplyAsync(() -> {
            // If you want a real transaction:
            // try (ClientSession session = mongoClient.startSession()) {
            //     session.startTransaction();
            //     try {
            //         T result = callback.doInTransaction(null); // not passing a JDBC connection
            //         session.commitTransaction();
            //         return result;
            //     } catch (Exception e) {
            //         session.abortTransaction();
            //         throw new RuntimeException("Transaction failed, rolled back.", e);
            //     }
            // }
            // Without real transaction:
            try {
                // We can't pass a JDBC Connection; the interface is shared with MySQL
                // Just run the callback.
                return callback.doInTransaction((Connection) null);
            } catch (Exception e) {
                throw new RuntimeException("Pseudo-transaction failed.", e);
            }
        }, executor);
    }

    private MongoDatabase getDatabase() {
        return mongoClient.getDatabase(databaseName);
    }

    private Map<String, Object> documentToMap(Document doc) {
        // Convert a BSON Document to a Map<String,Object>
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            map.put(entry.getKey(), entry.getValue());
        }
        return map;
    }
}
