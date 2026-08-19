package nl.hauntedmc.dataprovider.core.database.keyvalue.impl.redis;

import nl.hauntedmc.dataprovider.core.ManagedDatabaseProvider;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.logging.RateLimitedLogger;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.RedisProtocol;
import redis.clients.jedis.SslOptions;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/** Redis key-value provider backed by the shared Redis execution lane. */
public class RedisDatabase implements KeyValueDatabaseProvider, ManagedDatabaseProvider {

    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._:\\-\\[\\]]+");

    private final CommentedConfigurationNode config;
    private final LoggerAdapter logger;
    private final ExecutionHandle execution;
    private final RateLimitedLogger outageLogger = new RateLimitedLogger(Duration.ofSeconds(30));
    private volatile RedisClient redisClient;
    private volatile RedisDataAccess dataAccess;
    private volatile boolean connected;
    private volatile Throwable lifecycleFailure;
    private volatile int scanCount;
    private volatile int maxScanResults;
    private volatile int connectionPoolSize;

    public RedisDatabase(CommentedConfigurationNode config, LoggerAdapter logger) {
        this(config, logger, ExecutionHandle.direct());
    }

    public RedisDatabase(CommentedConfigurationNode config, LoggerAdapter logger, ExecutionHandle execution) {
        this.config = Objects.requireNonNull(config, "Config cannot be null.");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
        this.execution = Objects.requireNonNull(execution, "Execution handle cannot be null.");
    }

    @Override
    public synchronized void connect() {
        if (connected && isOpen(redisClient)) {
            logger.info("[RedisDatabase] Already connected; skipping re-initialization.");
            return;
        }
        RedisClient createdClient = null;
        try {
            String host = requireHost(config.node("host").getString("localhost"));
            int port = requireInRange(config.node("port").getInt(6379), 1, 65_535, "port");
            String user = config.node("user").getString("");
            String password = config.node("password").getString("");
            int databaseIndex = requireInRange(config.node("database").getInt(0), 0, 65_535, "database");
            int connectionPoolSize = requireInRange(config.node("pool", "connections").getInt(8),
                    1, 256, "pool.connections");
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
            int scanCount = requireInRange(config.node("scan_count").getInt(250), 1, 10_000, "scan_count");
            int maxScanResults = requireInRange(
                    config.node("security", "max_scan_results").getInt(10_000),
                    1, 1_000_000, "security.max_scan_results");
            boolean tlsEnabled = config.node("tls", "enabled").getBoolean(false);
            boolean verifyHostname = config.node("tls", "verify_hostname").getBoolean(true);
            boolean trustAllCertificates = config.node("tls", "trust_all_certificates").getBoolean(false);
            String trustStorePath = config.node("tls", "trust_store_path").getString("");
            String trustStorePassword = config.node("tls", "trust_store_password").getString("");
            String trustStoreType = config.node("tls", "trust_store_type").getString("");
            boolean requireSecureTransport = config.node("require_secure_transport").getBoolean(false);

            if (requireSecureTransport && !tlsEnabled) {
                throw new IllegalStateException("Redis require_secure_transport=true but tls.enabled=false");
            }
            if (!tlsEnabled) {
                logger.info("[RedisDatabase] Redis connection is running without TLS.");
            } else if (!verifyHostname || trustAllCertificates) {
                throw new IllegalStateException("Redis hostname and certificate verification cannot be disabled.");
            }
            if (!user.isBlank() && password.isBlank()) {
                logger.warn("[RedisDatabase] Redis user is configured without a password.");
            }

            ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
            poolConfig.setMaxTotal(connectionPoolSize);
            poolConfig.setMaxIdle(maxIdleConnections);
            poolConfig.setMinIdle(minIdleConnections);
            poolConfig.setTestOnBorrow(config.node("pool", "test_on_borrow").getBoolean(true));
            poolConfig.setTestWhileIdle(config.node("pool", "test_while_idle").getBoolean(true));
            poolConfig.setBlockWhenExhausted(true);

            DefaultJedisClientConfig.Builder clientConfigBuilder = DefaultJedisClientConfig.builder()
                    .protocol(RedisProtocol.RESP3)
                    .user(user.isBlank() ? null : user)
                    .password(password.isBlank() ? null : password)
                    .database(databaseIndex)
                    .connectionTimeoutMillis(connectionTimeoutMs)
                    .socketTimeoutMillis(socketTimeoutMs)
                    .ssl(tlsEnabled);
            if (tlsEnabled) {
                clientConfigBuilder.sslOptions(createSslOptions(trustStorePath, trustStorePassword, trustStoreType));
            }

            createdClient = RedisClient.builder()
                    .hostAndPort(host, port)
                    .clientConfig(clientConfigBuilder.build())
                    .poolConfig(poolConfig)
                    .build();
            if (!"PONG".equalsIgnoreCase(createdClient.ping())) {
                throw new IllegalStateException("Redis ping check failed.");
            }

            redisClient = createdClient;
            this.connectionPoolSize = connectionPoolSize;
            this.scanCount = scanCount;
            this.maxScanResults = maxScanResults;
            dataAccess = new RedisDataAccess(redisClient, execution, scanCount, maxScanResults);
            connected = true;
            lifecycleFailure = null;
            logger.info(String.format(
                    "[RedisDatabase] Connected to Redis at %s:%d (DB %d, protocol=RESP3, auth=%s, tls=%s, connectionPool=%d)",
                    host, port, databaseIndex, !password.isBlank() ? "enabled" : "disabled",
                    tlsEnabled ? "enabled" : "disabled", connectionPoolSize));
        } catch (Exception e) {
            lifecycleFailure = e;
            closeQuietly(createdClient);
            connected = false;
            redisClient = null;
            dataAccess = null;
            outageLogger.error(logger, "[RedisDatabase] Connection failed. (" + e.getClass().getSimpleName() + ").");
        }
    }

    @Override
    public synchronized void disconnect() {
        execution.close();
        if (isOpen(redisClient)) {
            redisClient.close();
            logger.info("[RedisDatabase] RedisClient closed.");
        }
        redisClient = null;
        dataAccess = null;
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected && isOpen(redisClient);
    }

    @Override
    public Throwable lifecycleFailure() {
        return lifecycleFailure;
    }

    @Override
    public boolean probeRemoteHealth() {
        RedisClient snapshot = redisClient;
        if (!connected || !isOpen(snapshot)) {
            return false;
        }
        try {
            return "PONG".equalsIgnoreCase(snapshot.ping());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public KeyValueDataAccess getDataAccess() {
        return dataAccess;
    }

    public int executionCapacity() {
        if (!isConnected() || connectionPoolSize < 1) {
            throw new IllegalStateException("[RedisDatabase] Redis client not initialized!");
        }
        return connectionPoolSize;
    }

    /** Creates a logical provider view without creating another Redis client or connection pool. */
    public KeyValueDatabaseProvider scoped(ExecutionHandle scopedExecution) {
        RedisClient source = redisClient;
        if (!connected || !isOpen(source)) {
            throw new IllegalStateException("[RedisDatabase] Redis client not initialized!");
        }
        KeyValueDataAccess accessView = new RedisDataAccess(source, scopedExecution, scanCount, maxScanResults);
        return new KeyValueDatabaseProvider() {
            @Override public boolean isConnected() { return RedisDatabase.this.isConnected() && !scopedExecution.isClosed(); }
            @Override public KeyValueDataAccess getDataAccess() { return accessView; }
        };
    }

    private static SslOptions createSslOptions(String trustStorePath, String trustStorePassword, String trustStoreType) {
        SslOptions.Builder builder = SslOptions.builder();
        if (trustStorePath == null || trustStorePath.isBlank()) {
            return builder.build();
        }
        char[] password = trustStorePassword == null || trustStorePassword.isEmpty()
                ? null : trustStorePassword.toCharArray();
        try {
            if (trustStoreType != null && !trustStoreType.isBlank()) {
                builder.trustStoreType(trustStoreType);
            }
            return builder.truststore(new File(trustStorePath), password).build();
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    private static boolean isOpen(RedisClient client) {
        return client != null && !client.getPool().isClosed();
    }

    private static void closeQuietly(RedisClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // Best-effort cleanup while preserving the original connection failure.
            }
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Redis config '" + fieldName + "' cannot be null or blank.");
        }
        return value.trim();
    }

    private static String requireHost(String host) {
        String normalized = requireNonBlank(host, "host");
        if (!HOST_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Redis config 'host' contains unsupported characters: " + normalized);
        }
        return normalized;
    }

    private static int requireInRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("Redis config '" + fieldName + "' must be between " + min + " and " + max
                    + ", but got " + value + ".");
        }
        return value;
    }
}
