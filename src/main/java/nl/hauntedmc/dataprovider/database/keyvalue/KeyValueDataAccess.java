package nl.hauntedmc.dataprovider.database.keyvalue;

import nl.hauntedmc.dataprovider.database.base.BaseDataAccess;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Defines methods for basic key–value operations as well as some advanced features.
 */
public interface KeyValueDataAccess extends BaseDataAccess {

    CompletableFuture<Void> setKey(String key, String value);

    CompletableFuture<String> getKey(String key);

    CompletableFuture<Void> deleteKey(String key);

    CompletableFuture<List<Map<String, Object>>> queryByPattern(String pattern);

    CompletableFuture<Void> setKeyWithExpiry(String key, String value, int ttlSeconds);

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
