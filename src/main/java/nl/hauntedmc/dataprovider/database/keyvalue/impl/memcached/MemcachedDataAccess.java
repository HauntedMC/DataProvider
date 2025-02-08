package nl.hauntedmc.dataprovider.database.keyvalue.impl.memcached;

import net.spy.memcached.CASResponse;
import net.spy.memcached.CASValue;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.internal.OperationFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MemcachedDataAccess implements KeyValueDataAccess using Spymemcached.
 */
public class MemcachedDataAccess implements KeyValueDataAccess {

    private final MemcachedClient memcachedClient;
    private final ExecutorService executor;

    // 0 means "never expire" in Memcached.
    private static final int DEFAULT_EXPIRY_SECS = 0;

    public MemcachedDataAccess(MemcachedClient memcachedClient, ExecutorService executor) {
        this.memcachedClient = memcachedClient;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Void> setKey(String key, String value) {
        return CompletableFuture.runAsync(() -> {
            OperationFuture<Boolean> future = memcachedClient.set(key, DEFAULT_EXPIRY_SECS, value);
            try {
                Boolean success = future.get(2, TimeUnit.SECONDS);
                if (!Boolean.TRUE.equals(success)) {
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
                future.get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException("Memcached deleteKey error: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> queryByPattern(String pattern) {
        throw new UnsupportedOperationException("Memcached does not support key scanning by pattern.");
    }

    @Override
    public CompletableFuture<Void> setKeyWithExpiry(String key, String value, int ttlSeconds) {
        return CompletableFuture.runAsync(() -> {
            try {
                OperationFuture<Boolean> future = memcachedClient.set(key, ttlSeconds, value);
                Boolean success = future.get(2, TimeUnit.SECONDS);
                if (!Boolean.TRUE.equals(success)) {
                    throw new RuntimeException("Failed to set key with expiry: " + key);
                }
            } catch (Exception e) {
                throw new RuntimeException("Memcached setKeyWithExpiry error: " + e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> pipelineSet(Map<String, String> entries) {
        return CompletableFuture.runAsync(() -> {
            List<OperationFuture<Boolean>> futures = new ArrayList<>();
            for (Map.Entry<String, String> e : entries.entrySet()) {
                OperationFuture<Boolean> future = memcachedClient.set(e.getKey(), DEFAULT_EXPIRY_SECS, e.getValue());
                futures.add(future);
            }
            for (OperationFuture<Boolean> f : futures) {
                try {
                    Boolean success = f.get(2, TimeUnit.SECONDS);
                    if (!Boolean.TRUE.equals(success)) {
                        throw new RuntimeException("Pipeline set failed for an entry.");
                    }
                } catch (Exception ex) {
                    throw new RuntimeException("Memcached pipelineSet error: " + ex.getMessage(), ex);
                }
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> watchCompareAndSet(String key, String oldValue, String newValue) {
        return CompletableFuture.supplyAsync(() -> {
            CASValue<Object> casVal = memcachedClient.gets(key);
            if (casVal == null) {
                return oldValue == null;
            }
            if (!Objects.equals(casVal.getValue().toString(), oldValue)) {
                return false;
            }
            CASResponse response = memcachedClient.cas(key, casVal.getCas(), DEFAULT_EXPIRY_SECS, newValue);
            return response == CASResponse.OK;
        }, executor);
    }

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
