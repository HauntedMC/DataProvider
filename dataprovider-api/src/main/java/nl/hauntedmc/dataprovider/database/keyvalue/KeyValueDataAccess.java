package nl.hauntedmc.dataprovider.database.keyvalue;

import nl.hauntedmc.dataprovider.database.DataAccess;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Defines methods for basic key–value operations as well as some advanced features.
 */
public interface KeyValueDataAccess extends DataAccess {

    CompletableFuture<Void> setKey(String key, String value);

    CompletableFuture<String> getKey(String key);

    /**
     * Returns the stored value or the supplied fallback when the key does not exist.
     */
    default CompletableFuture<String> getKeyOrDefault(String key, String defaultValue) {
        Objects.requireNonNull(defaultValue, "Default value cannot be null.");
        return getKey(key).thenApply(value -> value == null ? defaultValue : value);
    }

    CompletableFuture<Void> deleteKey(String key);

    CompletableFuture<List<Map<String, Object>>> queryByPattern(String pattern);

    CompletableFuture<Void> setKeyWithExpiry(String key, String value, int ttlSeconds);

    /**
     * Sets a key with a positive duration, rounding sub-second precision up to the next whole second.
     */
    default CompletableFuture<Void> setKeyWithExpiry(String key, String value, Duration ttl) {
        Objects.requireNonNull(ttl, "TTL cannot be null.");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("TTL must be positive.");
        }
        long seconds = ttl.getSeconds();
        if (ttl.getNano() > 0) {
            seconds = Math.addExact(seconds, 1L);
        }
        if (seconds > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("TTL cannot exceed " + Integer.MAX_VALUE + " seconds.");
        }
        return setKeyWithExpiry(key, value, (int) seconds);
    }

    CompletableFuture<Void> pipelineSet(Map<String, String> entries);

    CompletableFuture<Boolean> watchCompareAndSet(String key, String oldValue, String newValue);

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
