package nl.hauntedmc.dataprovider.database.keyvalue.impl.redis;

import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import nl.hauntedmc.dataprovider.logging.DPLogger;
import org.bukkit.configuration.file.FileConfiguration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RedisDatabase implements KeyValueDatabaseProvider, managing a JedisPool and an ExecutorService.
 */
public class RedisDatabase implements KeyValueDatabaseProvider {

    private final FileConfiguration config;

    private JedisPool jedisPool;
    private ExecutorService executor;
    private RedisDataAccess dataAccess;

    private boolean connected;

    public RedisDatabase(FileConfiguration config) {
        this.config = config;
    }

    @Override
    public void connect() {
        if (connected && jedisPool != null) {
            DPLogger.info("[RedisDatabase] Already connected; skipping re–initialization.");
            return;
        }
        try {
            final String host = config.getString("host", "localhost");
            final int port = config.getInt("port", 6379);
            final String password = config.getString("password", null);
            final int databaseIndex = config.getInt("database", 0);
            final int poolSize = config.getInt("pool_size", 8);

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
            DPLogger.info(String.format("[RedisDatabase] Connected to Redis at %s:%d (DB %d), poolSize=%d", host, port, databaseIndex, poolSize));
        } catch (Exception e) {
            DPLogger.error("[RedisDatabase] Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void disconnect() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            DPLogger.info("[RedisDatabase] JedisPool closed.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            DPLogger.info("[RedisDatabase] ExecutorService shut down.");
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
