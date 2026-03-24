package nl.hauntedmc.dataprovider.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import nl.hauntedmc.dataprovider.database.security.TlsSupport;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import redis.clients.jedis.*;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Production‑ready Redis back‑end using ACL user/password and Pub/Sub.
 */
public final class RedisMessagingDatabase implements MessagingDatabaseProvider {

    private final CommentedConfigurationNode cfg;
    private final ILoggerAdapter logger;
    private final MessageRegistry messageRegistry;
    private JedisPool pool;
    private ExecutorService workers;
    private RedisMessagingDataAccess bus;
    private volatile boolean connected;

    public RedisMessagingDatabase(CommentedConfigurationNode cfg, ILoggerAdapter logger) {
        this.cfg = cfg;
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
        this.messageRegistry = new MessageRegistry(logger);
    }

    @Override
    public synchronized void connect() {
        if (connected) return;

        String host = cfg.node("host").getString("localhost");
        int port = cfg.node("port").getInt(6379);
        int db = cfg.node("database").getInt(0);
        String user = cfg.node("user").getString(null);
        String pass = cfg.node("password").getString("");
        int connectionPoolSize = Math.max(1, cfg.node("pool", "connections").getInt(4));
        int workerPoolSize = Math.max(1, cfg.node("pool", "threads").getInt(8));
        int maxSubscriptions = Math.max(1, cfg.node("pool", "max_subscriptions").getInt(64));
        boolean tlsEnabled = cfg.node("tls", "enabled").getBoolean(false);
        boolean verifyHostname = cfg.node("tls", "verify_hostname").getBoolean(true);
        boolean trustAllCertificates = cfg.node("tls", "trust_all_certificates").getBoolean(false);
        boolean requireSecureTransport = cfg.node("require_secure_transport").getBoolean(false);

        if (requireSecureTransport && !tlsEnabled) {
            throw new IllegalStateException("Redis messaging require_secure_transport=true but tls.enabled=false");
        }
        if (!tlsEnabled) {
            logger.warn("[RedisMessagingDatabase] Redis messaging is running without TLS.");
        } else if (!verifyHostname || trustAllCertificates) {
            logger.warn("[RedisMessagingDatabase] Redis messaging TLS uses relaxed verification settings.");
        }

        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(connectionPoolSize);

            DefaultJedisClientConfig.Builder clientConfigBuilder = DefaultJedisClientConfig.builder()
                    .user((user == null || user.isBlank()) ? null : user)
                    .password(pass.isEmpty() ? null : pass)
                    .database(db)
                    .ssl(tlsEnabled);
            if (tlsEnabled && trustAllCertificates) {
                clientConfigBuilder.sslSocketFactory(TlsSupport.createTrustAllSslContext().getSocketFactory());
            }
            if (tlsEnabled && !verifyHostname) {
                clientConfigBuilder.hostnameVerifier(TlsSupport.trustAllHostnameVerifier());
            }
            DefaultJedisClientConfig clientConfig = clientConfigBuilder.build();

            HostAndPort nodeHp = new HostAndPort(host, port);
            pool = new JedisPool(poolConfig, nodeHp, clientConfig);

            try (Jedis jedis = pool.getResource()) {
                jedis.ping();
            }

            workers = Executors.newFixedThreadPool(workerPoolSize);
            bus = new RedisMessagingDataAccess(pool, workers, logger, messageRegistry, maxSubscriptions);
            connected = true;

            logger.info(String.format(
                    "[RedisMessagingDatabase] Connected to Redis messaging at %s:%d (db=%d, auth=%s, tls=%s, maxSubscriptions=%d)",
                    host,
                    port,
                    db,
                    pass.isEmpty() ? "disabled" : "enabled",
                    tlsEnabled ? "enabled" : "disabled",
                    maxSubscriptions
            ));
        } catch (Exception e) {
            connected = false;
            cleanupResources();
            logger.error("[RedisMessagingDatabase] Connection failed.", e);
        }
    }

    @Override
    public synchronized void disconnect() {
        if (!connected) return;
        try {
            if (bus != null) {
                bus.shutdown().get(3, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            logger.warn("[RedisMessagingDatabase] Timed out while shutting down subscriptions.");
        } finally {
            cleanupResources();
        }
        connected = false;
    }

    @Override
    public boolean isConnected() {
        if (!connected || pool == null || pool.isClosed()) {
            return false;
        }
        try (Jedis jedis = pool.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public MessagingDataAccess getDataAccess() {
        return bus;
    }

    private void cleanupResources() {
        if (workers != null) {
            workers.shutdown();
            try {
                if (!workers.awaitTermination(2, TimeUnit.SECONDS)) {
                    workers.shutdownNow();
                }
            } catch (InterruptedException ignored) {
                workers.shutdownNow();
                Thread.currentThread().interrupt();
            }
            workers = null;
        }

        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
        pool = null;
        bus = null;
    }
}
