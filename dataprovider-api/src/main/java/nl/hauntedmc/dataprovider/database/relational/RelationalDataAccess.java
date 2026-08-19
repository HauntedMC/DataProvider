package nl.hauntedmc.dataprovider.database.relational;

import nl.hauntedmc.dataprovider.database.DataAccess;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * DataAccess methods specific to relational (SQL) usage.
 *
 * <p>Query arguments are trusted SQL statement text owned by the calling application. Never
 * concatenate untrusted values into a query; use {@code ?} placeholders and the accompanying
 * parameter arguments instead.
 */
public interface RelationalDataAccess extends DataAccess {

    CompletableFuture<Void> executeUpdate(String query, Object... params);

    CompletableFuture<Map<String, Object>> queryForSingle(String query, Object... params);

    /** Returns the first matching row as an {@link Optional}. */
    default CompletableFuture<Optional<Map<String, Object>>> queryForSingleOptional(
            String query,
            Object... params
    ) {
        return queryForSingle(query, params).thenApply(Optional::ofNullable);
    }

    CompletableFuture<List<Map<String, Object>>> queryForList(String query, Object... params);

    CompletableFuture<Object> queryForSingleValue(String query, Object... params);

    /**
     * Queries one scalar value and casts it to the requested result type when the future completes.
     */
    default <T> CompletableFuture<T> queryForSingleValueAs(
            Class<T> resultType,
            String query,
            Object... params
    ) {
        Objects.requireNonNull(resultType, "Result type cannot be null.");
        return queryForSingleValue(query, params).thenApply(resultType::cast);
    }

    /** Queries one scalar value as an {@link Optional} of the requested type. */
    default <T> CompletableFuture<Optional<T>> queryForSingleValueOptionalAs(
            Class<T> resultType,
            String query,
            Object... params
    ) {
        Objects.requireNonNull(resultType, "Result type cannot be null.");
        return queryForSingleValue(query, params).thenApply(value -> Optional.ofNullable(resultType.cast(value)));
    }

    CompletableFuture<Void> executeBatchUpdate(String query, List<Object[]> batchParams);

    <T> CompletableFuture<T> executeTransactionally(TransactionCallback<T> callback);

    CompletableFuture<Object> executeInsert(String query, Object... params);

    /**
     * Executes an insert and casts the generated key to the requested result type when available.
     */
    default <T> CompletableFuture<T> executeInsertAs(
            Class<T> generatedKeyType,
            String query,
            Object... params
    ) {
        Objects.requireNonNull(generatedKeyType, "Generated key type cannot be null.");
        return executeInsert(query, params).thenApply(generatedKeyType::cast);
    }

    /** Executes an insert and returns the generated key as an {@link Optional}. */
    default <T> CompletableFuture<Optional<T>> executeInsertOptionalAs(
            Class<T> generatedKeyType,
            String query,
            Object... params
    ) {
        Objects.requireNonNull(generatedKeyType, "Generated key type cannot be null.");
        return executeInsert(query, params).thenApply(value -> Optional.ofNullable(generatedKeyType.cast(value)));
    }
}
