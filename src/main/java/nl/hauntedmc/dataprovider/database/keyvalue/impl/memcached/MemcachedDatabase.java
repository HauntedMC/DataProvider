package nl.hauntedmc.dataprovider.database.keyvalue.impl.memcached;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import org.spongepowered.configurate.CommentedConfigurationNode;
import net.spy.memcached.MemcachedClient;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MemcachedDatabase implements KeyValueDatabaseProvider using Spymemcached.
 */
public class MemcachedDatabase implements KeyValueDatabaseProvider {

    private final CommentedConfigurationNode config;
    private MemcachedClient memcachedClient;
    private ExecutorService executor;
    private MemcachedDataAccess dataAccess;
    private boolean connected;

    public MemcachedDatabase(CommentedConfigurationNode config) {
        this.config = config;
    }

    @Override
    public void connect() {
        if (connected && memcachedClient != null) {
            DataProvider.getLogger().info("[MemcachedDatabase] Already connected; skipping re–initialization.");
            return;
        }
        try {
            final String host = config.node("host").getString("localhost");
            final int port = config.node("port").getInt(11211);
            final int poolSize = Math.max(1, config.node("pool_size").getInt(8));

            memcachedClient = new MemcachedClient(new InetSocketAddress(host, port));
            DataProvider.getLogger().info(String.format("[MemcachedDatabase] Connected to Memcached at %s:%d", host, port));

            executor = Executors.newFixedThreadPool(poolSize);
            dataAccess = new MemcachedDataAccess(memcachedClient, executor);

            connected = true;
        } catch (Exception e) {
            DataProvider.getLogger().error("[MemcachedDatabase] Connection failed.", e);
        }
    }

    @Override
    public void disconnect() {
        if (memcachedClient != null) {
            memcachedClient.shutdown();
            DataProvider.getLogger().info("[MemcachedDatabase] MemcachedClient shut down.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            DataProvider.getLogger().info("[MemcachedDatabase] ExecutorService shut down.");
        }
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected && memcachedClient != null;
    }

    @Override
    public KeyValueDataAccess getDataAccess() {
        return dataAccess;
    }
}
