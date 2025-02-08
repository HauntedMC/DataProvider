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
 * MemcachedDatabase implements KeyValueDatabaseProvider,
 * using Spymemcached to handle the underlying connections.
 */
public class MemcachedDatabase implements KeyValueDatabaseProvider {

    private final FileConfiguration config;
    private final Logger logger;

    private MemcachedClient memcachedClient;
    private ExecutorService executor;
    private MemcachedDataAccess dataAccess;
    private boolean connected = false;

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
            logger.info("[MemcachedDatabase] Already connected; skipping re-initialization.");
            return;
        }
        try {
            String host = config.getString("host", "localhost");
            int port = config.getInt("port", 11211);
            int poolSize = config.getInt("pool_size", 8);

            // Create the Spymemcached client
            memcachedClient = new MemcachedClient(new InetSocketAddress(host, port));
            logger.info("[MemcachedDatabase] Connected to Memcached at " + host + ":" + port);

            // Executor for async
            executor = Executors.newFixedThreadPool(poolSize);

            // Create DataAccess
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
