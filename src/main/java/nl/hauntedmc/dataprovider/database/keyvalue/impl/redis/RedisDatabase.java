package nl.hauntedmc.dataprovider.database.keyvalue.impl.redis;

import nl.hauntedmc.dataprovider.internal.concurrent.BoundedExecutorFactory;
import nl.hauntedmc.dataprovider.internal.ManagedDatabaseProvider;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import nl.hauntedmc.dataprovider.database.security.TlsSupport;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import javax.net.ssl.SSLContext;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * RedisDatabase implements KeyValueDatabaseProvider, managing a JedisPool and an ExecutorService.
 */
public class RedisDatabase implements KeyValueDatabaseProvider, ManagedDatabaseProvider {

    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._:\\-\\[\\]]+");

    private final CommentedConfigurationNode config;
    private final LoggerAdapter logger;
    private volatile JedisPool jedisPool;
    private volatile ExecutorService executor;
    private volatile RedisDataAccess dataAccess;
    private volatile boolean connected;

    public RedisDatabase(CommentedConfigurationNode config, LoggerAdapter logger) {
        this.config = config;
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    @Override
    public synchronized void connect() {
        if (connected && jedisPool != null) {
            logger.info("[RedisDatabase] Already connected; skipping re–initialization.");
            return;
        }
        JedisPool createdPool = null;
        ExecutorService createdExecutor = null;
        try {
            final String host = requireHost(config.node("host").getString("localhost"));
            final int port = requireInRange(config.node("port").getInt(6379), 1, 65_535, "port");
            final String user = config.node("user").getString("");
            final String password = config.node("password").getString("");
            final int databaseIndex = requireInRange(config.node("database").getInt(0), 0, 65_535, "database");
            final int connectionPoolSize = requireInRange(
                    config.node("pool", "connections").getInt(8),
                    1,
                    256,
                    "pool.connections"
            );
            final int workerPoolSize = requireInRange(
                    config.node("pool", "threads").getInt(connectionPoolSize),
                    1,
                    256,
                    "pool.threads"
            );
            final int queueCapacity = requireInRange(
                    config.node("pool", "queue_capacity").getInt(workerPoolSize * 200),
                    workerPoolSize,
                    1_000_000,
                    "pool.queue_capacity"
            );
            final int maxIdleConnections = requireInRange(
                    config.node("pool", "max_idle").getInt(connectionPoolSize),
                    0,
                    connectionPoolSize,
                    "pool.max_idle"
            );
            final int minIdleConnections = requireInRange(
                    config.node("pool", "min_idle").getInt(Math.min(2, connectionPoolSize)),
                    0,
                    maxIdleConnections,
                    "pool.min_idle"
            );
            final int connectionTimeoutMs = requireInRange(
                    config.node("connection_timeout_ms").getInt(2_000),
                    250,
                    300_000,
                    "connection_timeout_ms"
            );
            final int socketTimeoutMs = requireInRange(
                    config.node("socket_timeout_ms").getInt(2_000),
                    250,
                    300_000,
                    "socket_timeout_ms"
            );
            final int scanCount = requireInRange(config.node("scan_count").getInt(250), 1, 10_000, "scan_count");
            final int maxScanResults = requireInRange(
                    config.node("security", "max_scan_results").getInt(10_000),
                    1,
                    1_000_000,
                    "security.max_scan_results"
            );
            final boolean tlsEnabled = config.node("tls", "enabled").getBoolean(false);
            final boolean verifyHostname = config.node("tls", "verify_hostname").getBoolean(true);
            final boolean trustAllCertificates = config.node("tls", "trust_all_certificates").getBoolean(false);
            final String trustStorePath = config.node("tls", "trust_store_path").getString("");
            final String trustStorePassword = config.node("tls", "trust_store_password").getString("");
            final String trustStoreType = config.node("tls", "trust_store_type").getString("");
            final boolean requireSecureTransport = config.node("require_secure_transport").getBoolean(false);

            if (requireSecureTransport && !tlsEnabled) {
                throw new IllegalStateException("Redis require_secure_transport=true but tls.enabled=false");
            }
            if (!tlsEnabled) {
                logger.warn("[RedisDatabase] Redis connection is running without TLS.");
            } else if (!verifyHostname || trustAllCertificates) {
                throw new IllegalStateException(
                        "Redis tls.verify_hostname must be true and tls.trust_all_certificates must be false in DataProvider 2.0."
                );
            }
            if (!user.isBlank() && password.isBlank()) {
                logger.warn("[RedisDatabase] Redis user is configured without a password.");
            }

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
                    .database(databaseIndex)
                    .connectionTimeoutMillis(connectionTimeoutMs)
                    .socketTimeoutMillis(socketTimeoutMs)
                    .ssl(tlsEnabled);
            if (tlsEnabled) {
                SSLContext sslContext = TlsSupport.createSslContext(trustStorePath, trustStorePassword, trustStoreType);
                clientConfigBuilder.sslSocketFactory(sslContext.getSocketFactory());
                clientConfigBuilder.hostnameVerifier(TlsSupport.strictHostnameVerifier());
            }

            DefaultJedisClientConfig clientConfig = clientConfigBuilder.build();

            createdPool = new JedisPool(poolConfig, new HostAndPort(host, port), clientConfig);
            try (var jedis = createdPool.getResource()) {
                if (!"PONG".equalsIgnoreCase(jedis.ping())) {
                    throw new IllegalStateException("Redis ping check failed.");
                }
            }

            createdExecutor = BoundedExecutorFactory.create("dataprovider-redis", workerPoolSize, queueCapacity);

            jedisPool = createdPool;
            executor = createdExecutor;
            dataAccess = new RedisDataAccess(jedisPool, executor, scanCount, maxScanResults);

            connected = true;
            logger.info(String.format(
                    "[RedisDatabase] Connected to Redis at %s:%d (DB %d, auth=%s, tls=%s), connectionPool=%d, workerPool=%d, queueCapacity=%d",
                    host,
                    port,
                    databaseIndex,
                    !password.isBlank() ? "enabled" : "disabled",
                    tlsEnabled ? "enabled" : "disabled",
                    connectionPoolSize,
                    workerPoolSize,
                    queueCapacity
            ));
        } catch (Exception e) {
            if (createdExecutor != null) {
                createdExecutor.shutdownNow();
            }
            if (createdPool != null && !createdPool.isClosed()) {
                createdPool.close();
            }
            connected = false;
            dataAccess = null;
            logger.error("[RedisDatabase] Connection failed.", e);
        }
    }

    @Override
    public synchronized void disconnect() {
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
            logger.info("[RedisDatabase] ExecutorService shut down.");
        }
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            logger.info("[RedisDatabase] JedisPool closed.");
        }
        executor = null;
        jedisPool = null;
        dataAccess = null;
        connected = false;
    }

    @Override
    public boolean isConnected() {
        JedisPool snapshot = jedisPool;
        if (!connected || snapshot == null || snapshot.isClosed()) {
            return false;
        }
        try (var jedis = snapshot.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public KeyValueDataAccess getDataAccess() {
        return dataAccess;
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
