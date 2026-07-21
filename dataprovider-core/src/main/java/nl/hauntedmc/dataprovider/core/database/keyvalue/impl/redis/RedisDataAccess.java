package nl.hauntedmc.dataprovider.core.database.keyvalue.impl.redis;

import nl.hauntedmc.dataprovider.core.concurrent.AsyncTaskSupport;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Redis data access routed through the shared Redis lane. */
final class RedisDataAccess implements KeyValueDataAccess {

    private final JedisPool jedisPool;
    private final Executor executor;
    private final int scanCount;
    private final int maxScanResults;

    RedisDataAccess(JedisPool jedisPool, Executor executor) {
        this(jedisPool, executor, 250, 10_000);
    }

    RedisDataAccess(JedisPool jedisPool, Executor executor, int scanCount, int maxScanResults) {
        this.jedisPool = Objects.requireNonNull(jedisPool, "Jedis pool cannot be null.");
        this.executor = Objects.requireNonNull(executor, "Executor cannot be null.");
        this.scanCount = Math.max(1, scanCount);
        this.maxScanResults = Math.max(1, maxScanResults);
    }

    @Override
    public CompletableFuture<Void> setKey(String key, String value) {
        String validatedKey = requireKey(key);
        Objects.requireNonNull(value, "Value cannot be null.");
        return AsyncTaskSupport.runAsync(executor, "redis.setKey", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.set(validatedKey, value);
            }
        });
    }

    @Override
    public CompletableFuture<String> getKey(String key) {
        String validatedKey = requireKey(key);
        return AsyncTaskSupport.supplyAsync(executor, "redis.getKey", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                return jedis.get(validatedKey);
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteKey(String key) {
        String validatedKey = requireKey(key);
        return AsyncTaskSupport.runAsync(executor, "redis.deleteKey", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del(validatedKey);
            }
        });
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> queryByPattern(String pattern) {
        String validatedPattern = requirePattern(pattern);
        return AsyncTaskSupport.supplyAsync(executor, "redis.queryByPattern", () -> {
            List<Map<String, Object>> results = new ArrayList<>();
            try (Jedis jedis = jedisPool.getResource()) {
                String cursor = ScanParams.SCAN_POINTER_START;
                ScanParams scanParams = new ScanParams().match(validatedPattern).count(scanCount);
                do {
                    ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                    cursor = scanResult.getCursor();
                    if (!scanResult.getResult().isEmpty()) {
                        Pipeline pipeline = jedis.pipelined();
                        Map<String, Response<String>> keyValues = new LinkedHashMap<>();
                        for (String foundKey : scanResult.getResult()) {
                            keyValues.put(foundKey, pipeline.get(foundKey));
                        }
                        pipeline.sync();
                        for (Map.Entry<String, Response<String>> entry : keyValues.entrySet()) {
                            String value = entry.getValue().get();
                            if (value != null) {
                                Map<String, Object> resultEntry = new HashMap<>();
                                resultEntry.put("key", entry.getKey());
                                resultEntry.put("value", value);
                                results.add(resultEntry);
                                if (results.size() >= maxScanResults) {
                                    return results;
                                }
                            }
                        }
                    }
                } while (!ScanParams.SCAN_POINTER_START.equals(cursor) && results.size() < maxScanResults);
            }
            return results;
        });
    }

    @Override
    public CompletableFuture<Void> setKeyWithExpiry(String key, String value, int ttlSeconds) {
        String validatedKey = requireKey(key);
        Objects.requireNonNull(value, "Value cannot be null.");
        if (ttlSeconds < 1) {
            throw new IllegalArgumentException("TTL seconds must be greater than zero.");
        }
        return AsyncTaskSupport.runAsync(executor, "redis.setKeyWithExpiry", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.setex(validatedKey, ttlSeconds, value);
            }
        });
    }

    @Override
    public CompletableFuture<Void> pipelineSet(Map<String, String> entries) {
        if (entries == null || entries.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Map<String, String> safeEntries = Map.copyOf(entries);
        safeEntries.forEach((key, value) -> {
            requireKey(key);
            Objects.requireNonNull(value, "Pipeline value cannot be null for key " + key);
        });
        return AsyncTaskSupport.runAsync(executor, "redis.pipelineSet", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                Pipeline pipeline = jedis.pipelined();
                safeEntries.forEach(pipeline::set);
                pipeline.sync();
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> watchCompareAndSet(String key, String oldValue, String newValue) {
        String validatedKey = requireKey(key);
        Objects.requireNonNull(newValue, "New value cannot be null.");
        return AsyncTaskSupport.supplyAsync(executor, "redis.watchCompareAndSet", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.watch(validatedKey);
                if (!Objects.equals(jedis.get(validatedKey), oldValue)) {
                    jedis.unwatch();
                    return false;
                }
                Transaction transaction = jedis.multi();
                transaction.set(validatedKey, newValue);
                List<Object> result = transaction.exec();
                return result != null && !result.isEmpty();
            }
        });
    }

    @Override
    public CompletableFuture<Void> hset(String hashKey, Map<String, String> fields) {
        String validatedHashKey = requireKey(hashKey);
        if (fields == null || fields.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Map<String, String> safeFields = Map.copyOf(fields);
        safeFields.forEach((field, value) -> {
            requireKey(field);
            Objects.requireNonNull(value, "Hash value cannot be null for field " + field);
        });
        return AsyncTaskSupport.runAsync(executor, "redis.hset", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hset(validatedHashKey, safeFields);
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, String>> hgetAll(String hashKey) {
        String validatedHashKey = requireKey(hashKey);
        return AsyncTaskSupport.supplyAsync(executor, "redis.hgetAll", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                return jedis.hgetAll(validatedHashKey);
            }
        });
    }

    @Override
    public CompletableFuture<Void> hdel(String hashKey, String... fields) {
        String validatedHashKey = requireKey(hashKey);
        if (fields == null || fields.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        String[] safeFields = fields.clone();
        for (String field : safeFields) {
            requireKey(field);
        }
        return AsyncTaskSupport.runAsync(executor, "redis.hdel", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hdel(validatedHashKey, safeFields);
            }
        });
    }

    @Override
    public CompletableFuture<Void> sadd(String key, String... members) {
        String validatedKey = requireKey(key);
        if (members == null || members.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        String[] safeMembers = members.clone();
        for (String member : safeMembers) {
            Objects.requireNonNull(member, "Set member cannot be null.");
        }
        return AsyncTaskSupport.runAsync(executor, "redis.sadd", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.sadd(validatedKey, safeMembers);
            }
        });
    }

    @Override
    public CompletableFuture<Set<String>> smembers(String key) {
        String validatedKey = requireKey(key);
        return AsyncTaskSupport.supplyAsync(executor, "redis.smembers", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                return jedis.smembers(validatedKey);
            }
        });
    }

    @Override
    public CompletableFuture<Void> srem(String key, String... members) {
        String validatedKey = requireKey(key);
        if (members == null || members.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        String[] safeMembers = members.clone();
        for (String member : safeMembers) {
            Objects.requireNonNull(member, "Set member cannot be null.");
        }
        return AsyncTaskSupport.runAsync(executor, "redis.srem", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.srem(validatedKey, safeMembers);
            }
        });
    }

    @Override
    public CompletableFuture<Void> zadd(String key, double score, String member) {
        String validatedKey = requireKey(key);
        Objects.requireNonNull(member, "Sorted set member cannot be null.");
        return AsyncTaskSupport.runAsync(executor, "redis.zadd", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.zadd(validatedKey, score, member);
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> zrangeByScore(String key, double min, double max) {
        String validatedKey = requireKey(key);
        return AsyncTaskSupport.supplyAsync(executor, "redis.zrangeByScore", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                return new ArrayList<>(jedis.zrangeByScore(validatedKey, min, max));
            }
        });
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Redis key cannot be null or blank.");
        }
        return key;
    }

    private static String requirePattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("Redis pattern cannot be null or blank.");
        }
        return pattern;
    }
}
