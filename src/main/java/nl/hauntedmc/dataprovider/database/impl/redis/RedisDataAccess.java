package nl.hauntedmc.dataprovider.database.impl.redis;

import com.google.gson.Gson;
import nl.hauntedmc.dataprovider.database.access.DataAccess;
import nl.hauntedmc.dataprovider.database.access.TransactionCallback;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Transaction;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Redis-based DataAccess.
 *
 * This is VERY naive. We treat "executeUpdate" as setting a key, "queryForSingle" as getting one, etc.
 * You can adapt this to your actual Redis usage patterns (hashes, lists, sets, JSON modules, etc.).
 */
public class RedisDataAccess implements DataAccess {

    private final JedisPool jedisPool;
    private final ExecutorService executor;
    private final Gson gson = new Gson(); // for JSON serialization if needed

    public RedisDataAccess(JedisPool jedisPool, ExecutorService executor) {
        this.jedisPool = jedisPool;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Void> executeUpdate(String query, Object... params) {
        // Let's interpret:
        // query = the Redis key
        // params[0] = the Map to store as JSON
        // e.g. set a key with JSON representation of data
        return CompletableFuture.runAsync(() -> {
            if (params.length < 1) {
                throw new IllegalArgumentException("No data provided for Redis update.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) params[0];
            String json = gson.toJson(data);

            try (Jedis jedis = jedisPool.getResource()) {
                jedis.set(query, json);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Map<String, Object>> queryForSingle(String query, Object... params) {
        // We'll interpret "query" as the Redis key.
        // We'll fetch the JSON and deserialize to Map.
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String json = jedis.get(query);
                if (json == null) return null;
                @SuppressWarnings("unchecked")
                Map<String, Object> map = gson.fromJson(json, Map.class);
                return map;
            }
        }, executor);
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> queryForList(String query, Object... params) {
        // Redis doesn't do sets of rows by default.
        // We'll interpret "query" as a pattern (like "user:*")
        // then we get all matching keys, retrieve them, and build a list.
        return CompletableFuture.supplyAsync(() -> {
            List<Map<String, Object>> resultList = new ArrayList<>();
            try (Jedis jedis = jedisPool.getResource()) {
                Set<String> keys = jedis.keys(query); // e.g. "user:*"
                for (String key : keys) {
                    String json = jedis.get(key);
                    if (json != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = gson.fromJson(json, Map.class);
                        resultList.add(map);
                    }
                }
            }
            return resultList;
        }, executor);
    }

    @Override
    public CompletableFuture<Object> queryForSingleValue(String query, Object... params) {
        // Possibly do a GET for the key and return a single field?
        // For example, if the value is a single string or number.
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                return jedis.get(query);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> executeBatchUpdate(String query, List<Object[]> batchParams) {
        // For a "batch" update, we might store multiple keys.
        // e.g. query = a prefix, then each Object[] has [keySuffix, dataMap].
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                for (Object[] param : batchParams) {
                    String keySuffix = (String) param[0];
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) param[1];
                    String json = gson.toJson(data);
                    jedis.set(query + keySuffix, json);
                }
            }
        }, executor);
    }

    @Override
    public <T> CompletableFuture<T> executeTransactionally(TransactionCallback<T> callback) {
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                // Start the Redis transaction
                Transaction t = jedis.multi();

                // Because our callback is expecting a JDBC Connection, we have nothing to pass in.
                // So this callback can't do any actual Redis commands. It's effectively a no-op.
                T result = callback.doInTransaction(null);

                // Execute the queued commands (if any).
                // But since we didn’t queue anything, this does nothing.
                t.exec();

                return result;
            } catch (Exception e) {
                // If something goes wrong in the callback, Jedis won't commit anything
                // We could discard() if we had the Transaction object in scope still.
                throw new RuntimeException("Redis transaction failed.", e);
            }
        }, executor);
    }

}
