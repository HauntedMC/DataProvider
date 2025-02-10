package nl.hauntedmc.dataprovider.database.keyvalue.impl.redis;

import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * RedisDataAccess implements KeyValueDataAccess using Jedis.
 */
 class RedisDataAccess implements KeyValueDataAccess {

    private final JedisPool jedisPool;
    private final ExecutorService executor;

    public RedisDataAccess(JedisPool jedisPool, ExecutorService executor) {
        this.jedisPool = jedisPool;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Void> setKey(String key, String value) {
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.set(key, value);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<String> getKey(String key) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                return jedis.get(key);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> deleteKey(String key) {
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.del(key);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> queryByPattern(String pattern) {
        return CompletableFuture.supplyAsync(() -> {
            List<Map<String, Object>> results = new ArrayList<>();
            try (Jedis jedis = jedisPool.getResource()) {
                String cursor = ScanParams.SCAN_POINTER_START;
                ScanParams scanParams = new ScanParams().match(pattern).count(100);

                do {
                    ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                    cursor = scanResult.getCursor();
                    for (String foundKey : scanResult.getResult()) {
                        String val = jedis.get(foundKey);
                        if (val != null) {
                            Map<String, Object> entry = new HashMap<>();
                            entry.put("key", foundKey);
                            entry.put("value", val);
                            results.add(entry);
                        }
                    }
                } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
            }
            return results;
        }, executor);
    }

    @Override
    public CompletableFuture<Void> setKeyWithExpiry(String key, String value, int ttlSeconds) {
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.setex(key, ttlSeconds, value);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> pipelineSet(Map<String, String> entries) {
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                Pipeline pipeline = jedis.pipelined();
                for (Map.Entry<String, String> e : entries.entrySet()) {
                    pipeline.set(e.getKey(), e.getValue());
                }
                pipeline.sync();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> watchCompareAndSet(String key, String oldValue, String newValue) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.watch(key);
                String currentVal = jedis.get(key);
                if (!Objects.equals(currentVal, oldValue)) {
                    jedis.unwatch();
                    return false;
                }
                Transaction t = jedis.multi();
                t.set(key, newValue);
                List<Object> result = t.exec();
                return (result != null && !result.isEmpty());
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> hset(String hashKey, Map<String, String> fields) {
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hset(hashKey, fields);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Map<String, String>> hgetAll(String hashKey) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                return jedis.hgetAll(hashKey);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> hdel(String hashKey, String... fields) {
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.hdel(hashKey, fields);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> sadd(String key, String... members) {
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.sadd(key, members);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Set<String>> smembers(String key) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                return jedis.smembers(key);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> srem(String key, String... members) {
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.srem(key, members);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> zadd(String key, double score, String member) {
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.zadd(key, score, member);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<String>> zrangeByScore(String key, double min, double max) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                Set<String> rawSet = (Set<String>) jedis.zrangeByScore(key, min, max);
                return new ArrayList<>(rawSet);
            }
        }, executor);
    }
}
