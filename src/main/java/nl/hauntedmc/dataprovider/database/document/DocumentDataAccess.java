package nl.hauntedmc.dataprovider.database.document;

import nl.hauntedmc.dataprovider.database.base.BaseDataAccess;
import nl.hauntedmc.dataprovider.database.document.model.DocumentQuery;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdate;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdateOptions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * DocumentDataAccess defines typical CRUD & index operations for
 * a document-based database in a technology-agnostic way.
 *
 * Each "document" is represented as a Map<String, Object> (or you could use JSON).
 * Queries and updates are represented by your own small DSL classes
 * (DocumentQuery, DocumentUpdate, DocumentUpdateOptions).
 */
public interface DocumentDataAccess extends BaseDataAccess {

    /**
     * Insert a single document (Map<String, Object>) into a collection.
     */
    CompletableFuture<Void> insertOne(String collection, Map<String, Object> document);

    /**
     * Find one document by a DocumentQuery. Returns null if none found.
     */
    CompletableFuture<Map<String, Object>> findOne(String collection, DocumentQuery query);

    /**
     * Find many documents by a DocumentQuery. Returns empty list if none found.
     */
    CompletableFuture<List<Map<String, Object>>> findMany(String collection, DocumentQuery query);

    /**
     * Update a single document matching a DocumentQuery.
     */
    CompletableFuture<Void> updateOne(String collection,
                                      DocumentQuery query,
                                      DocumentUpdate update,
                                      DocumentUpdateOptions options);

    /**
     * Update multiple documents matching a DocumentQuery.
     */
    CompletableFuture<Void> updateMany(String collection,
                                       DocumentQuery query,
                                       DocumentUpdate update,
                                       DocumentUpdateOptions options);

    /**
     * Delete one document matching a DocumentQuery.
     */
    CompletableFuture<Void> deleteOne(String collection, DocumentQuery query);

    /**
     * Delete many documents matching a DocumentQuery.
     */
    CompletableFuture<Void> deleteMany(String collection, DocumentQuery query);

    /**
     * Create an index on a collection with some key specification (Map or DSL).
     */
    CompletableFuture<Void> createIndex(String collection, Map<String, Object> indexSpec, Map<String, Object> indexOptions);

    /**
     * Drop an index by name on a collection.
     */
    CompletableFuture<Void> dropIndex(String collection, String indexName);
}
