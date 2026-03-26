package nl.hauntedmc.dataprovider.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.internal.concurrent.BoundedExecutorFactory;
import nl.hauntedmc.dataprovider.internal.ManagedDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import nl.hauntedmc.dataprovider.database.security.TlsSupport;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import redis.clients.jedis.*;

import javax.net.ssl.SSLContext;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Production‑ready Redis back‑end using ACL user/password and Pub/Sub.
 */
public final class RedisMessagingDatabase implements MessagingDatabaseProvider, ManagedDatabaseProvider {

    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._:\\-\\[\\]]+");

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

        String host = requireHost(cfg.node("host").getString("localhost"));
        int port = requireInRange(cfg.node("port").getInt(6379), 1, 65_535, "port");
        int db = requireInRange(cfg.node("database").getInt(0), 0, 65_535, "database");
        String user = cfg.node("user").getString("");
        String pass = cfg.node("password").getString("");
        int connectionPoolSize = requireInRange(cfg.node("pool", "connections").getInt(4), 1, 256, "pool.connections");
        int workerPoolSize = requireInRange(cfg.node("pool", "threads").getInt(8), 1, 256, "pool.threads");
        int workerQueueCapacity = requireInRange(
                cfg.node("pool", "queue_capacity").getInt(workerPoolSize * 200),
                workerPoolSize,
                1_000_000,
                "pool.queue_capacity"
        );
        int maxSubscriptions = requireInRange(cfg.node("pool", "max_subscriptions").getInt(64), 1, 10_000, "pool.max_subscriptions");
        int maxPayloadChars = requireInRange(
                cfg.node("security", "max_payload_chars").getInt(32_768),
                256,
                1_000_000,
                "security.max_payload_chars"
        );
        int maxQueuedMessagesPerHandler = requireInRange(
                cfg.node("security", "max_queued_messages_per_handler").getInt(1_024),
                1,
                1_000_000,
                "security.max_queued_messages_per_handler"
        );
        int maxIdleConnections = requireInRange(
                cfg.node("pool", "max_idle").getInt(connectionPoolSize),
                0,
                connectionPoolSize,
                "pool.max_idle"
        );
        int minIdleConnections = requireInRange(
                cfg.node("pool", "min_idle").getInt(Math.min(2, connectionPoolSize)),
                0,
                maxIdleConnections,
                "pool.min_idle"
        );
        int connectionTimeoutMs = requireInRange(
                cfg.node("connection_timeout_ms").getInt(2_000),
                250,
                300_000,
                "connection_timeout_ms"
        );
        int socketTimeoutMs = requireInRange(
                cfg.node("socket_timeout_ms").getInt(2_000),
                250,
                300_000,
                "socket_timeout_ms"
        );
        boolean tlsEnabled = cfg.node("tls", "enabled").getBoolean(false);
        boolean verifyHostname = cfg.node("tls", "verify_hostname").getBoolean(true);
        boolean trustAllCertificates = cfg.node("tls", "trust_all_certificates").getBoolean(false);
        String trustStorePath = cfg.node("tls", "trust_store_path").getString("");
        String trustStorePassword = cfg.node("tls", "trust_store_password").getString("");
        String trustStoreType = cfg.node("tls", "trust_store_type").getString("");
        boolean requireSecureTransport = cfg.node("require_secure_transport").getBoolean(false);

        if (requireSecureTransport && !tlsEnabled) {
            throw new IllegalStateException("Redis messaging require_secure_transport=true but tls.enabled=false");
        }
        if (!tlsEnabled) {
            logger.warn("[RedisMessagingDatabase] Redis messaging is running without TLS.");
        } else if (!verifyHostname || trustAllCertificates) {
            throw new IllegalStateException(
                    "Redis messaging tls.verify_hostname must be true and tls.trust_all_certificates must be false in DataProvider 2.0."
            );
        }
        if (!user.isBlank() && pass.isBlank()) {
            logger.warn("[RedisMessagingDatabase] Redis messaging user is configured without a password.");
        }

        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(connectionPoolSize);
            poolConfig.setMaxIdle(maxIdleConnections);
            poolConfig.setMinIdle(minIdleConnections);
            poolConfig.setTestOnBorrow(cfg.node("pool", "test_on_borrow").getBoolean(true));
            poolConfig.setTestWhileIdle(cfg.node("pool", "test_while_idle").getBoolean(true));
            poolConfig.setBlockWhenExhausted(true);

            DefaultJedisClientConfig.Builder clientConfigBuilder = DefaultJedisClientConfig.builder()
                    .user(user.isBlank() ? null : user)
                    .password(pass.isBlank() ? null : pass)
                    .database(db)
                    .connectionTimeoutMillis(connectionTimeoutMs)
                    .socketTimeoutMillis(socketTimeoutMs)
                    .ssl(tlsEnabled);
            if (tlsEnabled) {
                SSLContext sslContext = TlsSupport.createSslContext(trustStorePath, trustStorePassword, trustStoreType);
                clientConfigBuilder.sslSocketFactory(sslContext.getSocketFactory());
                clientConfigBuilder.hostnameVerifier(TlsSupport.strictHostnameVerifier());
            }
            DefaultJedisClientConfig clientConfig = clientConfigBuilder.build();

            HostAndPort nodeHp = new HostAndPort(host, port);
            pool = new JedisPool(poolConfig, nodeHp, clientConfig);

            try (Jedis jedis = pool.getResource()) {
                jedis.ping();
            }

            workers = BoundedExecutorFactory.create("dataprovider-redis-msg", workerPoolSize, workerQueueCapacity);
            bus = new RedisMessagingDataAccess(
                    pool,
                    workers,
                    logger,
                    messageRegistry,
                    maxSubscriptions,
                    maxPayloadChars,
                    maxQueuedMessagesPerHandler
            );
            connected = true;

            logger.info(String.format(
                    "[RedisMessagingDatabase] Connected to Redis messaging at %s:%d (db=%d, auth=%s, tls=%s, connectionPool=%d, workerPool=%d, maxSubscriptions=%d, queueCapacity=%d, maxPayloadChars=%d, maxQueuedMessagesPerHandler=%d)",
                    host,
                    port,
                    db,
                    pass.isBlank() ? "disabled" : "enabled",
                    tlsEnabled ? "enabled" : "disabled",
                    connectionPoolSize,
                    workerPoolSize,
                    maxSubscriptions,
                    workerQueueCapacity,
                    maxPayloadChars,
                    maxQueuedMessagesPerHandler
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
        JedisPool snapshot = pool;
        if (!connected || snapshot == null || snapshot.isClosed()) {
            return false;
        }
        try (Jedis jedis = snapshot.getResource()) {
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

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Redis messaging config '" + fieldName + "' cannot be null or blank.");
        }
        return value.trim();
    }

    private static String requireHost(String host) {
        String normalized = requireNonBlank(host, "host");
        if (!HOST_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Redis messaging config 'host' contains unsupported characters: " + normalized
            );
        }
        return normalized;
    }

    private static int requireInRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("Redis messaging config '" + fieldName + "' must be between "
                    + min + " and " + max + ", but got " + value + ".");
        }
        return value;
    }
}
