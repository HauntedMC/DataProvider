package nl.hauntedmc.dataprovider.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.logging.DPLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RedisMessagingDatabase implements MessagingDatabaseProvider using Redis Pub/Sub.
 */
public class RedisMessagingDatabase implements MessagingDatabaseProvider {

    private final ConfigurationSection config;

    private JedisPool jedisPool;
    private ExecutorService executor;
    private RedisMessagingDataAccess dataAccess;
    private boolean connected;

    public RedisMessagingDatabase(ConfigurationSection config) {
        this.config = config;
    }

    @Override
    public void connect() {
        if (connected && jedisPool != null) {
            DPLogger.info("[RedisMessagingDatabase] Already connected; skipping re–initialization.");
            return;
        }
        try {
            String host = config.getString("host", "localhost");
            int port = config.getInt("port", 6379);
            String password = config.getString("password", "");
            int databaseIndex = config.getInt("database", 0);
            int poolSize = config.getInt("pool_size", 4);

            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(poolSize);
            if (password != null && !password.isEmpty()) {
                jedisPool = new JedisPool(poolConfig, host, port, 2000, password, databaseIndex);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port, 2000, null, databaseIndex);
            }

            executor = Executors.newFixedThreadPool(poolSize);
            dataAccess = new RedisMessagingDataAccess(jedisPool, executor);
            connected = true;
            DPLogger.info("[RedisMessagingDatabase] Connected successfully to Redis for messaging.");
        } catch (Exception e) {
            DPLogger.error("[RedisMessagingDatabase] Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void disconnect() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            DPLogger.info("[RedisMessagingDatabase] JedisPool closed.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            DPLogger.info("[RedisMessagingDatabase] ExecutorService shut down.");
        }
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected && jedisPool != null && !jedisPool.isClosed();
    }

    @Override
    public MessagingDataAccess getDataAccess() {
        return dataAccess;
    }
}
