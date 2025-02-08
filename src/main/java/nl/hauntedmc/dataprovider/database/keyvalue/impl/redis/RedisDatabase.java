package nl.hauntedmc.dataprovider.database.keyvalue.impl.redis;

import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import org.bukkit.configuration.file.FileConfiguration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * RedisDatabase implements KeyValueDatabaseProvider, managing:
 *  - A JedisPool for connections
 *  - An ExecutorService for async tasks
 *  - A RedisDataAccess for actual read/write ops
 *
 * This lets us store data in Redis without blocking the main thread.
 */
public class RedisDatabase implements KeyValueDatabaseProvider {

    private final FileConfiguration config;
    private final Logger logger;

    private JedisPool jedisPool;
    private ExecutorService executor;
    private RedisDataAccess dataAccess;

    private boolean connected = false;

    /**
     * Constructor using FileConfiguration from "redis.yml"
     */
    public RedisDatabase(FileConfiguration config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    /**
     * Convenience constructor if you don't want to pass a logger separately.
     */
    public RedisDatabase(FileConfiguration config) {
        this(config, Logger.getLogger("RedisDatabase"));
    }

    @Override
    public void connect() {
        if (connected && jedisPool != null) {
            logger.info("[RedisDatabase] Already connected; skipping re-initialization.");
            return;
        }
        try {
            // Read config
            String host = config.getString("host", "localhost");
            int port = config.getInt("port", 6379);
            String password = config.getString("password", null);
            int databaseIndex = config.getInt("database", 0);

            // Pool size
            int poolSize = config.getInt("pool_size", 8);
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(poolSize);

            // Create JedisPool
            if (password != null && !password.isEmpty()) {
                jedisPool = new JedisPool(poolConfig, host, port, 2000, password, databaseIndex);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port, 2000, null, databaseIndex);
            }

            // Executor for async
            executor = Executors.newFixedThreadPool(poolSize);

            // DataAccess
            dataAccess = new RedisDataAccess(jedisPool, executor);

            connected = true;
            logger.info("[RedisDatabase] Connected to Redis at " + host + ":" + port
                    + " (DB " + databaseIndex + "), poolSize=" + poolSize);
        } catch (Exception e) {
            logger.severe("[RedisDatabase] Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void disconnect() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            logger.info("[RedisDatabase] JedisPool closed.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            logger.info("[RedisDatabase] ExecutorService shut down.");
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
