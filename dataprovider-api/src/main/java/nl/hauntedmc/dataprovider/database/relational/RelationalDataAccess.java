package nl.hauntedmc.dataprovider.database.relational;

import nl.hauntedmc.dataprovider.database.DataAccess;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * DataAccess methods specific to relational (SQL) usage.
 */
public interface RelationalDataAccess extends DataAccess {

    CompletableFuture<Void> executeUpdate(String query, Object... params);

    CompletableFuture<Map<String, Object>> queryForSingle(String query, Object... params);

    CompletableFuture<List<Map<String, Object>>> queryForList(String query, Object... params);

    CompletableFuture<Object> queryForSingleValue(String query, Object... params);

    CompletableFuture<Void> executeBatchUpdate(String query, List<Object[]> batchParams);

    <T> CompletableFuture<T> executeTransactionally(TransactionCallback<T> callback);

    CompletableFuture<Object> executeInsert(String sql, Object[] array);
}
