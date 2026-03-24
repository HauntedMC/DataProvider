package nl.hauntedmc.dataprovider.database.keyvalue.impl.memcached;

import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import net.spy.memcached.MemcachedClient;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * MemcachedDatabase implements KeyValueDatabaseProvider using Spymemcached.
 */
public class MemcachedDatabase implements KeyValueDatabaseProvider {

    private final CommentedConfigurationNode config;
    private final ILoggerAdapter logger;
    private MemcachedClient memcachedClient;
    private ExecutorService executor;
    private MemcachedDataAccess dataAccess;
    private boolean connected;

    public MemcachedDatabase(CommentedConfigurationNode config, ILoggerAdapter logger) {
        this.config = config;
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    @Override
    public void connect() {
        if (connected && memcachedClient != null) {
            logger.info("[MemcachedDatabase] Already connected; skipping re–initialization.");
            return;
        }
        MemcachedClient createdClient = null;
        ExecutorService createdExecutor = null;
        try {
            final String host = config.node("host").getString("localhost");
            final int port = config.node("port").getInt(11211);
            final int poolSize = Math.max(1, config.node("pool_size").getInt(8));

            createdClient = new MemcachedClient(new InetSocketAddress(host, port));
            if (createdClient.getAvailableServers().isEmpty()) {
                throw new IllegalStateException("Memcached has no available servers after connect.");
            }
            logger.info(String.format("[MemcachedDatabase] Connected to Memcached at %s:%d", host, port));

            createdExecutor = Executors.newFixedThreadPool(poolSize);

            memcachedClient = createdClient;
            executor = createdExecutor;
            dataAccess = new MemcachedDataAccess(memcachedClient, executor);
            connected = true;
        } catch (Exception e) {
            if (createdExecutor != null) {
                createdExecutor.shutdownNow();
            }
            if (createdClient != null) {
                createdClient.shutdown();
            }
            connected = false;
            dataAccess = null;
            logger.error("[MemcachedDatabase] Connection failed.", e);
        }
    }

    @Override
    public void disconnect() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("[MemcachedDatabase] ExecutorService shut down.");
        }
        if (memcachedClient != null) {
            memcachedClient.shutdown();
            logger.info("[MemcachedDatabase] MemcachedClient shut down.");
        }
        executor = null;
        memcachedClient = null;
        dataAccess = null;
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected
                && memcachedClient != null
                && !memcachedClient.getAvailableServers().isEmpty();
    }

    @Override
    public KeyValueDataAccess getDataAccess() {
        return dataAccess;
    }
}
