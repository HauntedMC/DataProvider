package nl.hauntedmc.dataprovider.database.document;

import nl.hauntedmc.dataprovider.database.base.BaseDataAccess;
import nl.hauntedmc.dataprovider.database.document.model.DocumentQuery;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdate;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdateOptions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * DocumentDataAccess defines typical CRUD and index operations for
 * a document–based database in a technology–agnostic way.
 *
 * Each "document" is represented as a Map<String, Object>.
 * Queries and updates are represented by custom DSL classes
 * (DocumentQuery, DocumentUpdate, DocumentUpdateOptions).
 */
public interface DocumentDataAccess extends BaseDataAccess {

    CompletableFuture<Void> insertOne(String collection, Map<String, Object> document);

    CompletableFuture<Map<String, Object>> findOne(String collection, DocumentQuery query);

    CompletableFuture<List<Map<String, Object>>> findMany(String collection, DocumentQuery query);

    CompletableFuture<Void> updateOne(String collection,
                                      DocumentQuery query,
                                      DocumentUpdate update,
                                      DocumentUpdateOptions options);

    CompletableFuture<Void> updateMany(String collection,
                                       DocumentQuery query,
                                       DocumentUpdate update,
                                       DocumentUpdateOptions options);

    CompletableFuture<Void> deleteOne(String collection, DocumentQuery query);

    CompletableFuture<Void> deleteMany(String collection, DocumentQuery query);

    CompletableFuture<Void> createIndex(String collection, Map<String, Object> indexSpec, Map<String, Object> indexOptions);

    CompletableFuture<Void> dropIndex(String collection, String indexName);
}
