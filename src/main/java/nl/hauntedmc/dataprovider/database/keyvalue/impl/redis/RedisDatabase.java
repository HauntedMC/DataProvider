package nl.hauntedmc.dataprovider.database.keyvalue.impl.redis;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import org.spongepowered.configurate.CommentedConfigurationNode;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
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
            DataProvider.getLogger().info("[RedisDatabase] Already connected; skipping re–initialization.");
            return;
        }
        try {
            final String host = config.node("host").getString("localhost");
            final int port = config.node("port").getInt(6379);
            final String user = config.node("user").getString(null);
            final String password = config.node("password").getString(null);
            final int databaseIndex = config.node("database").getInt(0);
            final int poolSize = Math.max(1,
                    config.node("pool_size").getInt(config.node("pool", "connections").getInt(8)));

            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(poolSize);

            DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                    .user((user == null || user.isBlank()) ? null : user)
                    .password((password == null || password.isBlank()) ? null : password)
                    .database(databaseIndex)
                    .connectionTimeoutMillis(2000)
                    .socketTimeoutMillis(2000)
                    .build();

            jedisPool = new JedisPool(poolConfig, new HostAndPort(host, port), clientConfig);

            executor = Executors.newFixedThreadPool(poolSize);
            dataAccess = new RedisDataAccess(jedisPool, executor);

            connected = true;
            DataProvider.getLogger().info(String.format(
                    "[RedisDatabase] Connected to Redis at %s:%d (DB %d, auth=%s), poolSize=%d",
                    host,
                    port,
                    databaseIndex,
                    (password != null && !password.isBlank()) ? "enabled" : "disabled",
                    poolSize
            ));
        } catch (Exception e) {
            DataProvider.getLogger().error("[RedisDatabase] Connection failed.", e);
        }
    }

    @Override
    public void disconnect() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            DataProvider.getLogger().info("[RedisDatabase] JedisPool closed.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            DataProvider.getLogger().info("[RedisDatabase] ExecutorService shut down.");
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
