package nl.hauntedmc.dataprovider.database.impl.redis;

import nl.hauntedmc.dataprovider.database.schema.ColumnDefinition;
import nl.hauntedmc.dataprovider.database.schema.SchemaManager;
import nl.hauntedmc.dataprovider.database.schema.TableDefinition;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class RedisSchemaManager implements SchemaManager {

    private final JedisPool jedisPool;
    private final ExecutorService executor;

    public RedisSchemaManager(JedisPool jedisPool, ExecutorService executor) {
        this.jedisPool = jedisPool;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Void> createTable(TableDefinition tableDefinition) {
        // Redis doesn't have the concept of "table creation."
        // We might no-op or do a simple pattern check, or set up initial keys.
        return CompletableFuture.runAsync(() -> {
            // No-op
        }, executor);
    }

    @Override
    public CompletableFuture<Void> alterTable(TableDefinition tableDefinition) {
        // Again, no concept of "alter" in Redis.
        return CompletableFuture.runAsync(() -> {
            // No-op
        }, executor);
    }

    @Override
    public CompletableFuture<Void> dropTable(String tableName) {
        // You might interpret this as deleting all keys matching a prefix, e.g. "tableName:*".
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String pattern = tableName + ":*";
                for (String key : jedis.keys(pattern)) {
                    jedis.del(key);
                }
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Boolean> tableExists(String tableName) {
        // A simplistic check: see if there are any keys with prefix tableName:
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String pattern = tableName + ":*";
                return !jedis.keys(pattern).isEmpty();
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> addIndex(String tableName, String column, boolean unique) {
        // Redis doesn't have indexes in the relational sense.
        // Could be a no-op or set up a sorted set, etc.
        return CompletableFuture.runAsync(() -> {
            // No-op
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeIndex(String tableName, String indexName) {
        // Also no direct concept of indexes to drop.
        return CompletableFuture.runAsync(() -> {
            // No-op
        }, executor);
    }

    @Override
    public CompletableFuture<Void> addForeignKey(String table, String column, String referenceTable, String referenceColumn) {
        // Redis does not have foreign keys. No-op.
        return CompletableFuture.runAsync(() -> {
            // No-op
        }, executor);
    }

    @Override
    public CompletableFuture<Void> removeForeignKey(String table, String constraintName) {
        // Redis does not have foreign keys. No-op.
        return CompletableFuture.runAsync(() -> {
            // No-op
        }, executor);
    }
}
