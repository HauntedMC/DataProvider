package nl.hauntedmc.dataprovider.database.impl.redis;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.access.DataAccess;
import nl.hauntedmc.dataprovider.database.schema.SchemaManager;
import org.bukkit.configuration.file.FileConfiguration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class RedisDatabase implements DatabaseProvider {

    private final FileConfiguration config;
    private final Logger logger;

    private JedisPool jedisPool;
    private ExecutorService executor;
    private DataAccess dataAccess;
    private SchemaManager schemaManager;

    public RedisDatabase(FileConfiguration config) {
        this.config = config;
        this.logger = DataProvider.getInstance().getLogger();
    }

    @Override
    public void connect() {
        if (jedisPool != null) {
            logger.info("[RedisDatabase] Already connected, skipping re-initialization.");
            return;
        }

        try {
            String host = getEnvOrConfig("DB_REDIS_HOST", config.getString("host", "localhost"));
            int port = Integer.parseInt(getEnvOrConfig("DB_REDIS_PORT", String.valueOf(config.getInt("port", 6379))));
            String password = getEnvOrConfig("DB_REDIS_PASS", config.getString("password", null));
            int database = config.getInt("database", 0);

            // Connection pool configuration
            int poolSize = config.getInt("pool_size", 8);
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(poolSize);

            // If there's no password, Jedis typically uses the 2-arg constructor.
            // For password-protected Redis, use another constructor with password.
            if (password != null && !password.isEmpty()) {
                jedisPool = new JedisPool(poolConfig, host, port, 2000, password, database);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port, 2000, null, database);
            }

            executor = Executors.newFixedThreadPool(poolSize);

            // Create DataAccess and SchemaManager
            this.dataAccess = new RedisDataAccess(jedisPool, executor);
            this.schemaManager = new RedisSchemaManager(jedisPool, executor);

            logger.info("[RedisDatabase] Connected to Redis at " + host + ":" + port + " (DB " + database + ")");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[RedisDatabase] Connection failed!", e);
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
    }

    @Override
    public boolean isConnected() {
        // JedisPool does not have a built-in "isValid" check.
        // We'll consider "connected" if jedisPool is not null and not closed.
        return jedisPool != null && !jedisPool.isClosed();
    }

    @Override
    public SchemaManager getSchemaManager() {
        if (schemaManager == null) {
            throw new IllegalStateException("[RedisDatabase] SchemaManager not initialized!");
        }
        return schemaManager;
    }

    @Override
    public DataAccess getDataAccess() {
        if (dataAccess == null) {
            throw new IllegalStateException("[RedisDatabase] DataAccess not initialized!");
        }
        return dataAccess;
    }

    /**
     * Utility: returns the environment variable if present, otherwise the fallback value.
     */
    private String getEnvOrConfig(String envKey, String fallback) {
        String envValue = System.getenv(envKey);
        return (envValue != null && !envValue.isEmpty()) ? envValue : fallback;
    }
}
