package nl.hauntedmc.dataprovider.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import org.spongepowered.configurate.CommentedConfigurationNode;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RedisMessagingDatabase implements MessagingDatabaseProvider using Redis Pub/Sub.
 */
public class RedisMessagingDatabase implements MessagingDatabaseProvider {

    private final CommentedConfigurationNode config;

    private JedisPool jedisPool;
    private ExecutorService executor;
    private RedisMessagingDataAccess dataAccess;
    private boolean connected;

    public RedisMessagingDatabase(CommentedConfigurationNode config) {
        this.config = config;
    }

    @Override
    public void connect() {
        if (connected && jedisPool != null) {
            DataProvider.getLogger().info("[RedisMessagingDatabase] Already connected; skipping re–initialization.");
            return;
        }
        try {
            String host = config.node("host").getString("localhost");
            int port = config.node("port").getInt(6379);
            String password = config.node("password").getString("");
            int databaseIndex = config.node("database").getInt(0);
            int poolSize = config.node("pool_size").getInt(4);

            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(poolSize);
            if (!password.isEmpty()) {
                jedisPool = new JedisPool(poolConfig, host, port, 2000, password, databaseIndex);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port, 2000, null, databaseIndex);
            }

            executor = Executors.newFixedThreadPool(poolSize);
            dataAccess = new RedisMessagingDataAccess(jedisPool, executor);
            connected = true;
            DataProvider.getLogger().info("[RedisMessagingDatabase] Connected successfully to Redis for messaging.");
        } catch (Exception e) {
            DataProvider.getLogger().error("[RedisMessagingDatabase] Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void disconnect() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            DataProvider.getLogger().info("[RedisMessagingDatabase] JedisPool closed.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            DataProvider.getLogger().info("[RedisMessagingDatabase] ExecutorService shut down.");
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
