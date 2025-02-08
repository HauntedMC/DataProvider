package nl.hauntedmc.dataprovider.database.keyvalue.impl.memcached;

import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import org.bukkit.configuration.file.FileConfiguration;
import net.spy.memcached.MemcachedClient;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * MemcachedDatabase implements KeyValueDatabaseProvider using Spymemcached.
 */
public class MemcachedDatabase implements KeyValueDatabaseProvider {

    private final FileConfiguration config;
    private final Logger logger;

    private MemcachedClient memcachedClient;
    private ExecutorService executor;
    private MemcachedDataAccess dataAccess;
    private boolean connected;

    public MemcachedDatabase(FileConfiguration config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public MemcachedDatabase(FileConfiguration config) {
        this(config, Logger.getLogger("MemcachedDatabase"));
    }

    @Override
    public void connect() {
        if (connected && memcachedClient != null) {
            logger.info("[MemcachedDatabase] Already connected; skipping re–initialization.");
            return;
        }
        try {
            final String host = config.getString("host", "localhost");
            final int port = config.getInt("port", 11211);
            final int poolSize = config.getInt("pool_size", 8);

            memcachedClient = new MemcachedClient(new InetSocketAddress(host, port));
            logger.info(String.format("[MemcachedDatabase] Connected to Memcached at %s:%d", host, port));

            executor = Executors.newFixedThreadPool(poolSize);
            dataAccess = new MemcachedDataAccess(memcachedClient, executor);

            connected = true;
        } catch (Exception e) {
            logger.severe("[MemcachedDatabase] Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void disconnect() {
        if (memcachedClient != null) {
            memcachedClient.shutdown();
            logger.info("[MemcachedDatabase] MemcachedClient shut down.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            logger.info("[MemcachedDatabase] ExecutorService shut down.");
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
