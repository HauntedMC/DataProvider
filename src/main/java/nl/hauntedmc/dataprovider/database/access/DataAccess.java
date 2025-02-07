package nl.hauntedmc.dataprovider.database.access;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface DataAccess {

    /**
     * Executes an insert, update, or delete statement.
     */
    CompletableFuture<Void> executeUpdate(String query, Object... params);

    /**
     * Executes a query and returns a single row.
     */
    CompletableFuture<Map<String, Object>> queryForSingle(String query, Object... params);

    /**
     * Executes a query and returns multiple rows.
     */
    CompletableFuture<List<Map<String, Object>>> queryForList(String query, Object... params);

    /**
     * Executes a query and returns a single value (e.g., COUNT, SUM).
     */
    CompletableFuture<Object> queryForSingleValue(String query, Object... params);

    /**
     * Executes a batch update.
     */
    CompletableFuture<Void> executeBatchUpdate(String query, List<Object[]> batchParams);

    /**
     * Begins a transaction.
     */
    CompletableFuture<Void> beginTransaction();

    /**
     * Commits a transaction.
     */
    CompletableFuture<Void> commitTransaction();

    /**
     * Rolls back a transaction.
     */
    CompletableFuture<Void> rollbackTransaction();
}
