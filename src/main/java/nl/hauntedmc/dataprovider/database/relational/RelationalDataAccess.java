package nl.hauntedmc.dataprovider.database.relational;

import nl.hauntedmc.dataprovider.database.base.BaseDataAccess;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * DataAccess methods specific to relational (SQL) usage.
 * This aligns with your existing methods for CRUD queries, transactions, etc.
 */
public interface RelationalDataAccess extends BaseDataAccess {

    CompletableFuture<Void> executeUpdate(String query, Object... params);

    CompletableFuture<Map<String, Object>> queryForSingle(String query, Object... params);

    CompletableFuture<List<Map<String, Object>>> queryForList(String query, Object... params);

    CompletableFuture<Object> queryForSingleValue(String query, Object... params);

    CompletableFuture<Void> executeBatchUpdate(String query, List<Object[]> batchParams);

    <T> CompletableFuture<T> executeTransactionally(TransactionCallback<T> callback);
}
