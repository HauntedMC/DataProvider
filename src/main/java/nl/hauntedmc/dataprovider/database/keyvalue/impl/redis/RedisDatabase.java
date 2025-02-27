package nl.hauntedmc.dataprovider.database.keyvalue.impl.redis;

import nl.hauntedmc.dataprovider.DataProviderApp;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import org.spongepowered.configurate.CommentedConfigurationNode;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RedisDatabase implements KeyValueDatabaseProvider, managing a JedisPool and an ExecutorService.
 */
public class RedisDatabase implements KeyValueDatabaseProvider {

    private final CommentedConfigurationNode config;
    private JedisPool jedisPool;
    private ExecutorService executor;
    private RedisDataAccess dataAccess;
    private boolean connected;

    public RedisDatabase(CommentedConfigurationNode config) {
        this.config = config;
    }

    @Override
    public void connect() {
        if (connected && jedisPool != null) {
            DataProviderApp.getLogger().info("[RedisDatabase] Already connected; skipping re–initialization.");
            return;
        }
        try {
            final String host = config.node("host").getString("localhost");
            final int port = config.node("port").getInt(6379);
            final String password = config.node("password").getString(null);
            final int databaseIndex = config.node("database").getInt(0);
            final int poolSize = config.node("pool_size").getInt(8);

            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(poolSize);

            if (password != null && !password.isEmpty()) {
                jedisPool = new JedisPool(poolConfig, host, port, 2000, password, databaseIndex);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port, 2000, null, databaseIndex);
            }

            executor = Executors.newFixedThreadPool(poolSize);
            dataAccess = new RedisDataAccess(jedisPool, executor);

            connected = true;
            DataProviderApp.getLogger().info(String.format("[RedisDatabase] Connected to Redis at %s:%d (DB %d), poolSize=%d", host, port, databaseIndex, poolSize));
        } catch (Exception e) {
            DataProviderApp.getLogger().error("[RedisDatabase] Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void disconnect() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            DataProviderApp.getLogger().info("[RedisDatabase] JedisPool closed.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            DataProviderApp.getLogger().info("[RedisDatabase] ExecutorService shut down.");
        }
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected && jedisPool != null && !jedisPool.isClosed();
    }

    @Override
    public KeyValueDataAccess getDataAccess() {
        return dataAccess;
    }
}
