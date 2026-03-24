package nl.hauntedmc.dataprovider.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import org.spongepowered.configurate.CommentedConfigurationNode;
import redis.clients.jedis.*;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Production‑ready Redis back‑end using ACL user/password and Pub/Sub.
 */
public final class RedisMessagingDatabase implements MessagingDatabaseProvider {

    private final CommentedConfigurationNode cfg;
    private JedisPool pool;
    private ExecutorService workers;
    private RedisMessagingDataAccess bus;
    private volatile boolean connected;

    public RedisMessagingDatabase(CommentedConfigurationNode cfg) {
        this.cfg = cfg;
    }

    @Override
    public synchronized void connect() {
        if (connected) return;

        String host = cfg.node("host").getString("localhost");
        int port = cfg.node("port").getInt(6379);
        int db = cfg.node("database").getInt(0);
        String user = cfg.node("user").getString("plugin");
        String pass = cfg.node("password").getString("");
        int connectionPoolSize = Math.max(1, cfg.node("pool", "connections").getInt(4));
        int workerPoolSize = Math.max(1, cfg.node("pool", "threads").getInt(8));

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(connectionPoolSize);

        DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .user(user)
                .password(pass.isEmpty() ? null : pass)
                .database(db)
                .build();

        HostAndPort nodeHp = new HostAndPort(host, port);
        pool = new JedisPool(poolConfig, nodeHp, clientConfig);

        workers = Executors.newFixedThreadPool(workerPoolSize);
        bus = new RedisMessagingDataAccess(pool, workers);

        connected = true;
    }

    @Override
    public synchronized void disconnect() {
        if (!connected) return;
        bus.shutdown();
        workers.shutdownNow();
        pool.close();
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public MessagingDataAccess getDataAccess() {
        return bus;
    }
}
