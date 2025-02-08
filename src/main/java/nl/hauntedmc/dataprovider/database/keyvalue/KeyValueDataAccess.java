package nl.hauntedmc.dataprovider.database.keyvalue;

import nl.hauntedmc.dataprovider.database.base.BaseDataAccess;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * KeyValueDataAccess defines methods for basic key-value stores,
 * plus some commonly used advanced features.
 *
 * This interface can be implemented by Redis, Memcached, or other
 * key-value storage systems.
 */
public interface KeyValueDataAccess extends BaseDataAccess {

    /**
     * Set a value in the DB by key (overwrites if key exists).
     */
    CompletableFuture<Void> setKey(String key, String value);

    /**
     * Get a value by key (returns null if not found).
     */
    CompletableFuture<String> getKey(String key);

    /**
     * Delete a key if it exists.
     */
    CompletableFuture<Void> deleteKey(String key);

    /**
     * Query or scan keys matching a pattern, returning a list of
     * { key=..., value=... } maps.
     * The meaning of "pattern" depends on the implementation
     * (e.g., Redis SCAN pattern, Memcached might not support it).
     */
    CompletableFuture<List<Map<String, Object>>> queryByPattern(String pattern);

    // -------------------------------------------------------
    // Optional advanced features - can be no-ops if unsupported
    // -------------------------------------------------------

    /**
     * Set a key with an expiry (TTL) in seconds.
     */
    CompletableFuture<Void> setKeyWithExpiry(String key, String value, int ttlSeconds);

    /**
     * Batch/pipeline multiple sets to reduce round trips.
     */
    CompletableFuture<Void> pipelineSet(Map<String, String> entries);

    /**
     * Concurrency control with compare-and-set semantics:
     * Only set 'newValue' if the current value is 'oldValue'.
     * Return true if updated, false otherwise.
     */
    CompletableFuture<Boolean> watchCompareAndSet(String key, String oldValue, String newValue);

    // ----------------------------
    // Common Data Structures
    // ----------------------------

    // Hash operations
    CompletableFuture<Void> hset(String hashKey, Map<String, String> fields);
    CompletableFuture<Map<String, String>> hgetAll(String hashKey);
    CompletableFuture<Void> hdel(String hashKey, String... fields);

    // Set operations
    CompletableFuture<Void> sadd(String key, String... members);
    CompletableFuture<Set<String>> smembers(String key);
    CompletableFuture<Void> srem(String key, String... members);

    // Sorted Set operations
    CompletableFuture<Void> zadd(String key, double score, String member);
    CompletableFuture<List<String>> zrangeByScore(String key, double min, double max);
}
