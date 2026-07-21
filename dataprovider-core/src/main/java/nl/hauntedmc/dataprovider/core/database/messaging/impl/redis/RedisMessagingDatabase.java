package nl.hauntedmc.dataprovider.core.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.core.ManagedDatabaseProvider;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.database.security.TlsSupport;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import javax.net.ssl.SSLContext;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Redis messaging provider backed by the shared messaging execution lane. */
public final class RedisMessagingDatabase implements MessagingDatabaseProvider, ManagedDatabaseProvider {

    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._:\\-\\[\\]]+");

    private final CommentedConfigurationNode config;
    private final LoggerAdapter logger;
    private final MessageRegistry messageRegistry;
    private final ExecutionHandle execution;
    private volatile JedisPool pool;
    private volatile RedisMessagingDataAccess bus;
    private volatile boolean connected;
    private volatile Throwable lifecycleFailure;

    public RedisMessagingDatabase(CommentedConfigurationNode config, LoggerAdapter logger) {
        this(config, logger, ExecutionHandle.direct());
    }

    public RedisMessagingDatabase(
            CommentedConfigurationNode config,
            LoggerAdapter logger,
            ExecutionHandle execution
    ) {
        this.config = Objects.requireNonNull(config, "Config cannot be null.");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
        this.messageRegistry = new MessageRegistry(logger);
        this.execution = Objects.requireNonNull(execution, "Execution handle cannot be null.");
    }

    @Override
    public synchronized void connect() {
        if (connected) {
            return;
        }

        String host = requireHost(config.node("host").getString("localhost"));
        int port = requireInRange(config.node("port").getInt(6379), 1, 65_535, "port");
        int database = requireInRange(config.node("database").getInt(0), 0, 65_535, "database");
        String user = config.node("user").getString("");
        String password = config.node("password").getString("");
        int connectionPoolSize = requireInRange(config.node("pool", "connections").getInt(4),
                1, 256, "pool.connections");
        int maxSubscriptions = requireInRange(config.node("pool", "max_subscriptions").getInt(64),
                1, 10_000, "pool.max_subscriptions");
        int maxPayloadChars = requireInRange(config.node("security", "max_payload_chars").getInt(32_768),
                256, 1_000_000, "security.max_payload_chars");
        int maxQueuedMessagesPerHandler = requireInRange(
                config.node("security", "max_queued_messages_per_handler").getInt(1_024),
                1, 1_000_000, "security.max_queued_messages_per_handler");
        int handlerBatchSize = requireInRange(config.node("pool", "handler_batch_size").getInt(64),
                1, 10_000, "pool.handler_batch_size");
        int maxIdleConnections = requireInRange(
                config.node("pool", "max_idle").getInt(connectionPoolSize),
                0, connectionPoolSize, "pool.max_idle");
        int minIdleConnections = requireInRange(
                config.node("pool", "min_idle").getInt(Math.min(2, connectionPoolSize)),
                0, maxIdleConnections, "pool.min_idle");
        int connectionTimeoutMs = requireInRange(config.node("connection_timeout_ms").getInt(2_000),
                250, 300_000, "connection_timeout_ms");
        int socketTimeoutMs = requireInRange(config.node("socket_timeout_ms").getInt(2_000),
                250, 300_000, "socket_timeout_ms");
        boolean tlsEnabled = config.node("tls", "enabled").getBoolean(false);
        boolean verifyHostname = config.node("tls", "verify_hostname").getBoolean(true);
        boolean trustAllCertificates = config.node("tls", "trust_all_certificates").getBoolean(false);
        String trustStorePath = config.node("tls", "trust_store_path").getString("");
        String trustStorePassword = config.node("tls", "trust_store_password").getString("");
        String trustStoreType = config.node("tls", "trust_store_type").getString("");
        boolean requireSecureTransport = config.node("require_secure_transport").getBoolean(false);

        if (requireSecureTransport && !tlsEnabled) {
            throw new IllegalStateException("Redis messaging require_secure_transport=true but tls.enabled=false");
        }
        if (!tlsEnabled) {
            logger.warn("[RedisMessagingDatabase] Redis messaging is running without TLS.");
        } else if (!verifyHostname || trustAllCertificates) {
            throw new IllegalStateException("Redis messaging hostname and certificate verification cannot be disabled.");
        }
        if (!user.isBlank() && password.isBlank()) {
            logger.warn("[RedisMessagingDatabase] Redis messaging user is configured without a password.");
        }

        JedisPool createdPool = null;
        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(connectionPoolSize);
            poolConfig.setMaxIdle(maxIdleConnections);
            poolConfig.setMinIdle(minIdleConnections);
            poolConfig.setTestOnBorrow(config.node("pool", "test_on_borrow").getBoolean(true));
            poolConfig.setTestWhileIdle(config.node("pool", "test_while_idle").getBoolean(true));
            poolConfig.setBlockWhenExhausted(true);

            DefaultJedisClientConfig.Builder clientConfigBuilder = DefaultJedisClientConfig.builder()
                    .user(user.isBlank() ? null : user)
                    .password(password.isBlank() ? null : password)
                    .database(database)
                    .connectionTimeoutMillis(connectionTimeoutMs)
                    .socketTimeoutMillis(socketTimeoutMs)
                    .ssl(tlsEnabled);
            if (tlsEnabled) {
                SSLContext sslContext = TlsSupport.createSslContext(trustStorePath, trustStorePassword, trustStoreType);
                clientConfigBuilder.sslSocketFactory(sslContext.getSocketFactory());
                clientConfigBuilder.hostnameVerifier(TlsSupport.strictHostnameVerifier());
            }

            createdPool = new JedisPool(poolConfig, new HostAndPort(host, port), clientConfigBuilder.build());
            try (Jedis jedis = createdPool.getResource()) {
                if (!"PONG".equalsIgnoreCase(jedis.ping())) {
                    throw new IllegalStateException("Redis messaging ping check failed.");
                }
            }

            pool = createdPool;
            bus = new RedisMessagingDataAccess(
                    pool,
                    execution,
                    logger,
                    messageRegistry,
                    maxSubscriptions,
                    maxPayloadChars,
                    maxQueuedMessagesPerHandler,
                    handlerBatchSize
            );
            connected = true;
            lifecycleFailure = null;
            logger.info(String.format(
                    "[RedisMessagingDatabase] Connected at %s:%d (db=%d, auth=%s, tls=%s, connectionPool=%d, maxSubscriptions=%d)",
                    host, port, database, password.isBlank() ? "disabled" : "enabled",
                    tlsEnabled ? "enabled" : "disabled", connectionPoolSize, maxSubscriptions));
        } catch (Exception e) {
            lifecycleFailure = e;
            connected = false;
            if (createdPool != null && !createdPool.isClosed()) {
                createdPool.close();
            }
            pool = null;
            bus = null;
            logger.error("[RedisMessagingDatabase] Connection failed.", e);
        }
    }

    @Override
    public synchronized void disconnect() {
        try {
            if (bus != null) {
                bus.shutdown().get(3, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            logger.warn("[RedisMessagingDatabase] Timed out while shutting down subscriptions.");
        } finally {
            execution.close();
            if (pool != null && !pool.isClosed()) {
                pool.close();
            }
            pool = null;
            bus = null;
            connected = false;
        }
    }

    @Override
    public boolean isConnected() {
        JedisPool snapshot = pool;
        return connected && snapshot != null && !snapshot.isClosed();
    }

    @Override
    public Throwable lifecycleFailure() {
        return lifecycleFailure;
    }

    @Override
    public boolean probeRemoteHealth() {
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
                    "Redis messaging config 'host' contains unsupported characters: " + normalized);
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
