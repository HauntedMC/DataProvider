package nl.hauntedmc.dataprovider.database.access;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Abstraction for data operations (CRUD).
 */
public interface DataAccess {

    /**
     * Executes an INSERT/UPDATE/DELETE statement asynchronously.
     */
    CompletableFuture<Void> executeUpdate(String query, Object... params);

    /**
     * Executes a query that returns a single row (or null if none).
     */
    CompletableFuture<Map<String, Object>> queryForSingle(String query, Object... params);

    /**
     * Executes a query that returns a list of rows.
     */
    CompletableFuture<List<Map<String, Object>>> queryForList(String query, Object... params);

    /**
     * Executes a query that returns a single value (e.g. COUNT, SUM).
     */
    CompletableFuture<Object> queryForSingleValue(String query, Object... params);

    /**
     * Executes a batch update with multiple parameter sets.
     */
    CompletableFuture<Void> executeBatchUpdate(String query, List<Object[]> batchParams);

    /**
     * Execute multiple statements within a single transaction block.
     * The function parameter should contain the logic to run inside the transaction.
     * You can return a result from the transaction if needed.
     *
     * Example usage:
     * <pre>
     *   dataAccess.executeTransactionally(connection -> {
     *       // do statements with the same connection
     *       // return something
     *   });
     * </pre>
     */
    <T> CompletableFuture<T> executeTransactionally(TransactionCallback<T> callback);
}
