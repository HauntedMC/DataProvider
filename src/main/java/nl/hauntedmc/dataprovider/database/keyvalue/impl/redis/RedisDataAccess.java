package nl.hauntedmc.dataprovider.database.keyvalue.impl.redis;

import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.internal.concurrent.AsyncTaskSupport;
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
import java.util.concurrent.ExecutorService;

/**
 * RedisDataAccess implements KeyValueDataAccess using Jedis.
 */
final class RedisDataAccess implements KeyValueDataAccess {

    private final JedisPool jedisPool;
    private final ExecutorService executor;
    private final int scanCount;
    private final int maxScanResults;

    public RedisDataAccess(JedisPool jedisPool, ExecutorService executor) {
        this(jedisPool, executor, 250, 10_000);
    }

    public RedisDataAccess(JedisPool jedisPool, ExecutorService executor, int scanCount, int maxScanResults) {
        this.jedisPool = Objects.requireNonNull(jedisPool, "Jedis pool cannot be null.");
        this.executor = Objects.requireNonNull(executor, "Executor cannot be null.");
        this.scanCount = Math.max(1, scanCount);
        this.maxScanResults = Math.max(1, maxScanResults);
    }

    @Override
    public CompletableFuture<Void> setKey(String key, String value) {
        final String validatedKey = requireKey(key);
        Objects.requireNonNull(value, "Value cannot be null.");
        return AsyncTaskSupport.runAsync(executor, "redis.setKey", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.set(validatedKey, value);
            }
        });
    }

    @Override
    public CompletableFuture<String> getKey(String key) {
        final String validatedKey = requireKey(key);
        return AsyncTaskSupport.supplyAsync(executor, "redis.getKey", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                return jedis.get(validatedKey);
            }
        });
    }

    @Override
    public CompletableFuture<Void> deleteKey(String key) {
        final String validatedKey = requireKey(key);
        return AsyncTaskSupport.runAsync(executor, "redis.deleteKey", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del(validatedKey);
            }
        });
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> queryByPattern(String pattern) {
        final String validatedPattern = requirePattern(pattern);
        return AsyncTaskSupport.supplyAsync(executor, "redis.queryByPattern", () -> {
            List<Map<String, Object>> results = new ArrayList<>();
            try (Jedis jedis = jedisPool.getResource()) {
                String cursor = ScanParams.SCAN_POINTER_START;
                ScanParams scanParams = new ScanParams().match(validatedPattern).count(scanCount);

                do {
                    ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                    cursor = scanResult.getCursor();
                    List<String> foundKeys = scanResult.getResult();
                    if (!foundKeys.isEmpty()) {
                        Pipeline pipeline = jedis.pipelined();
                        Map<String, Response<String>> keyValues = new LinkedHashMap<>();
                        for (String foundKey : foundKeys) {
                            keyValues.put(foundKey, pipeline.get(foundKey));
                        }
                        pipeline.sync();

                        for (Map.Entry<String, Response<String>> entry : keyValues.entrySet()) {
                            String val = entry.getValue().get();
                            if (val != null) {
                                Map<String, Object> resultEntry = new HashMap<>();
                                resultEntry.put("key", entry.getKey());
                                resultEntry.put("value", val);
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
        final String validatedKey = requireKey(key);
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
        for (Map.Entry<String, String> entry : safeEntries.entrySet()) {
            requireKey(entry.getKey());
            Objects.requireNonNull(entry.getValue(), "Pipeline value cannot be null for key " + entry.getKey());
        }
        return AsyncTaskSupport.runAsync(executor, "redis.pipelineSet", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                Pipeline pipeline = jedis.pipelined();
                for (Map.Entry<String, String> entry : safeEntries.entrySet()) {
                    pipeline.set(entry.getKey(), entry.getValue());
                }
                pipeline.sync();
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> watchCompareAndSet(String key, String oldValue, String newValue) {
        final String validatedKey = requireKey(key);
        Objects.requireNonNull(newValue, "New value cannot be null.");
        return AsyncTaskSupport.supplyAsync(executor, "redis.watchCompareAndSet", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.watch(validatedKey);
                String currentVal = jedis.get(validatedKey);
                if (!Objects.equals(currentVal, oldValue)) {
                    jedis.unwatch();
                    return false;
                }
                Transaction t = jedis.multi();
                t.set(validatedKey, newValue);
                List<Object> result = t.exec();
                return (result != null && !result.isEmpty());
            }
        });
    }

    @Override
    public CompletableFuture<Void> hset(String hashKey, Map<String, String> fields) {
        final String validatedHashKey = requireKey(hashKey);
        if (fields == null || fields.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Map<String, String> safeFields = Map.copyOf(fields);
        for (Map.Entry<String, String> entry : safeFields.entrySet()) {
            requireKey(entry.getKey());
            Objects.requireNonNull(entry.getValue(), "Hash value cannot be null for field " + entry.getKey());
        }
        return AsyncTaskSupport.runAsync(executor, "redis.hset", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hset(validatedHashKey, safeFields);
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, String>> hgetAll(String hashKey) {
        final String validatedHashKey = requireKey(hashKey);
        return AsyncTaskSupport.supplyAsync(executor, "redis.hgetAll", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                return jedis.hgetAll(validatedHashKey);
            }
        });
    }

    @Override
    public CompletableFuture<Void> hdel(String hashKey, String... fields) {
        final String validatedHashKey = requireKey(hashKey);
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
        final String validatedKey = requireKey(key);
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
        final String validatedKey = requireKey(key);
        return AsyncTaskSupport.supplyAsync(executor, "redis.smembers", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                return jedis.smembers(validatedKey);
            }
        });
    }

    @Override
    public CompletableFuture<Void> srem(String key, String... members) {
        final String validatedKey = requireKey(key);
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
        final String validatedKey = requireKey(key);
        Objects.requireNonNull(member, "Sorted set member cannot be null.");
        return AsyncTaskSupport.runAsync(executor, "redis.zadd", () -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.zadd(validatedKey, score, member);
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> zrangeByScore(String key, double min, double max) {
        final String validatedKey = requireKey(key);
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
