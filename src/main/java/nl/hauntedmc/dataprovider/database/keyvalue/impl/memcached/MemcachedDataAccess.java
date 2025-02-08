package nl.hauntedmc.dataprovider.database.keyvalue.impl.memcached;

import net.spy.memcached.CASResponse;
import net.spy.memcached.CASValue;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.internal.OperationFuture;

import java.util.*;
import java.util.concurrent.*;

/**
 * MemcachedDataAccess implements KeyValueDataAccess using Spymemcached.
 *
 * Some advanced Redis-like features (like scanning, sets, sorted sets) are either no-op or unsupported,
 * because Memcached doesn't provide such functionality out of the box.
 */
public class MemcachedDataAccess implements KeyValueDataAccess {

    private final MemcachedClient memcachedClient;
    private final ExecutorService executor;

    // Default expiry if needed
    private static final int DEFAULT_EXPIRY_SECS = 0; // 0 means "never expire" in Memcached

    public MemcachedDataAccess(MemcachedClient memcachedClient, ExecutorService executor) {
        this.memcachedClient = memcachedClient;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Void> setKey(String key, String value) {
        return CompletableFuture.runAsync(() -> {
            // Spymemcached's set operation returns an OperationFuture
            OperationFuture<Boolean> future = memcachedClient.set(key, DEFAULT_EXPIRY_SECS, value);
            try {
                // Wait for completion
                Boolean success = future.get(2, TimeUnit.SECONDS);
                if (!success) {
                    throw new RuntimeException("Failed to set key: " + key);
                }
            } catch (Exception e) {
                throw new RuntimeException("Memcached setKey error: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<String> getKey(String key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Object result = memcachedClient.get(key);
                return (result != null) ? result.toString() : null;
            } catch (Exception e) {
                throw new RuntimeException("Memcached getKey error: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> deleteKey(String key) {
        return CompletableFuture.runAsync(() -> {
            try {
                OperationFuture<Boolean> future = memcachedClient.delete(key);
                Boolean success = future.get(2, TimeUnit.SECONDS);
                if (!success) {
                    // It's okay if the key didn't exist,
                    // but spymemcached might return false if the delete fails.
                    // We'll just log/ignore or throw an exception if you want strict behavior.
                }
            } catch (Exception e) {
                throw new RuntimeException("Memcached deleteKey error: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> queryByPattern(String pattern) {
        // Memcached does NOT support scanning or pattern matching for keys.
        // We must either store key references ourselves or skip it entirely.
        // We'll throw an UnsupportedOperationException here:
        throw new UnsupportedOperationException("Memcached does not support key scanning by pattern.");
    }

    // -------------------------------------------------------
    // 2) Expiry
    // -------------------------------------------------------

    @Override
    public CompletableFuture<Void> setKeyWithExpiry(String key, String value, int ttlSeconds) {
        return CompletableFuture.runAsync(() -> {
            try {
                OperationFuture<Boolean> future = memcachedClient.set(key, ttlSeconds, value);
                Boolean success = future.get(2, TimeUnit.SECONDS);
                if (!success) {
                    throw new RuntimeException("Failed to set key with expiry: " + key);
                }
            } catch (Exception e) {
                throw new RuntimeException("Memcached setKeyWithExpiry error: " + e.getMessage(), e);
            }
        }, executor);
    }

    // -------------------------------------------------------
    // 3) Pipelining
    // -------------------------------------------------------
    @Override
    public CompletableFuture<Void> pipelineSet(Map<String, String> entries) {
        // Memcached doesn't have a "pipeline" concept like Redis.
        // We'll set keys sequentially or do a naive approach.
        // It's possible to do multiple asynchronous set operations in parallel, though.
        return CompletableFuture.runAsync(() -> {
            List<OperationFuture<Boolean>> futures = new ArrayList<>();
            for (Map.Entry<String, String> e : entries.entrySet()) {
                OperationFuture<Boolean> future = memcachedClient.set(
                        e.getKey(), DEFAULT_EXPIRY_SECS, e.getValue());
                futures.add(future);
            }
            // Wait for all
            for (OperationFuture<Boolean> f : futures) {
                try {
                    // We can wait for each operation's completion.
                    Boolean success = f.get(2, TimeUnit.SECONDS);
                    if (!success) {
                        throw new RuntimeException("Pipeline set failed for an entry.");
                    }
                } catch (Exception ex) {
                    throw new RuntimeException("Memcached pipelineSet error: " + ex.getMessage(), ex);
                }
            }
        }, executor);
    }

    // -------------------------------------------------------
    // 4) WATCH-based concurrency
    // -------------------------------------------------------
    @Override
    public CompletableFuture<Boolean> watchCompareAndSet(String key, String oldValue, String newValue) {
        // Memcached doesn't have a built-in WATCH/MULTI/EXEC like Redis.
        // We can do a CAS (Compare-And-Set) approach if we retrieve the CAS token.
        // Let's implement a naive CAS approach with spymemcached getCAS().
        return CompletableFuture.supplyAsync(() -> {
            // Attempt to retrieve the CAS token
            CASValue<Object> casVal = memcachedClient.gets(key);
            if (casVal == null) {
                // Key not found or does not exist.
                // If oldValue != null, mismatch -> return false.
                return (oldValue == null);
            }
            if (!Objects.equals(casVal.getValue().toString(), oldValue)) {
                return false;
            }
            // We have the correct oldValue, attempt CAS
            CASResponse response = memcachedClient.cas(
                    key, casVal.getCas(), Integer.parseInt(newValue), DEFAULT_EXPIRY_SECS
            );
            // CASResponse.OK if it succeeded
            return (response == CASResponse.OK);
        }, executor);
    }

    // -------------------------------------------------------
    // 5) Hash, Set, SortedSet
    // -------------------------------------------------------
    // Memcached doesn't have these data structures natively:
    @Override
    public CompletableFuture<Void> hset(String hashKey, Map<String, String> fields) {
        throw new UnsupportedOperationException("Memcached does not support hash operations.");
    }

    @Override
    public CompletableFuture<Map<String, String>> hgetAll(String hashKey) {
        throw new UnsupportedOperationException("Memcached does not support hash operations.");
    }

    @Override
    public CompletableFuture<Void> hdel(String hashKey, String... fields) {
        throw new UnsupportedOperationException("Memcached does not support hash operations.");
    }

    @Override
    public CompletableFuture<Void> sadd(String key, String... members) {
        throw new UnsupportedOperationException("Memcached does not support set operations.");
    }

    @Override
    public CompletableFuture<Set<String>> smembers(String key) {
        throw new UnsupportedOperationException("Memcached does not support set operations.");
    }

    @Override
    public CompletableFuture<Void> srem(String key, String... members) {
        throw new UnsupportedOperationException("Memcached does not support set operations.");
    }

    @Override
    public CompletableFuture<Void> zadd(String key, double score, String member) {
        throw new UnsupportedOperationException("Memcached does not support sorted set operations.");
    }

    @Override
    public CompletableFuture<List<String>> zrangeByScore(String key, double min, double max) {
        throw new UnsupportedOperationException("Memcached does not support sorted set operations.");
    }
}
