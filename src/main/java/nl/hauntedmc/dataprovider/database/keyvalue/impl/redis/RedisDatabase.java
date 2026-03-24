package nl.hauntedmc.dataprovider.database.keyvalue.impl.redis;

import nl.hauntedmc.dataprovider.internal.concurrent.BoundedExecutorFactory;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDataAccess;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import nl.hauntedmc.dataprovider.database.security.TlsSupport;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * RedisDatabase implements KeyValueDatabaseProvider, managing a JedisPool and an ExecutorService.
 */
public class RedisDatabase implements KeyValueDatabaseProvider {

    private final CommentedConfigurationNode config;
    private final ILoggerAdapter logger;
    private JedisPool jedisPool;
    private ExecutorService executor;
    private RedisDataAccess dataAccess;
    private boolean connected;

    public RedisDatabase(CommentedConfigurationNode config, ILoggerAdapter logger) {
        this.config = config;
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    @Override
    public void connect() {
        if (connected && jedisPool != null) {
            logger.info("[RedisDatabase] Already connected; skipping re–initialization.");
            return;
        }
        JedisPool createdPool = null;
        ExecutorService createdExecutor = null;
        try {
            final String host = config.node("host").getString("localhost");
            final int port = config.node("port").getInt(6379);
            final String user = config.node("user").getString(null);
            final String password = config.node("password").getString(null);
            final int databaseIndex = config.node("database").getInt(0);
            final int poolSize = Math.max(1,
                    config.node("pool_size").getInt(config.node("pool", "connections").getInt(8)));
            final int queueCapacity = Math.max(poolSize, config.node("queue_capacity").getInt(poolSize * 200));
            final boolean tlsEnabled = config.node("tls", "enabled").getBoolean(false);
            final boolean verifyHostname = config.node("tls", "verify_hostname").getBoolean(true);
            final boolean trustAllCertificates = config.node("tls", "trust_all_certificates").getBoolean(false);
            final boolean requireSecureTransport = config.node("require_secure_transport").getBoolean(false);

            if (requireSecureTransport && !tlsEnabled) {
                throw new IllegalStateException("Redis require_secure_transport=true but tls.enabled=false");
            }
            if (!tlsEnabled) {
                logger.warn("[RedisDatabase] Redis connection is running without TLS.");
            } else if (!verifyHostname || trustAllCertificates) {
                logger.warn("[RedisDatabase] Redis TLS is enabled with relaxed certificate/hostname verification.");
            }

            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(poolSize);

            DefaultJedisClientConfig.Builder clientConfigBuilder = DefaultJedisClientConfig.builder()
                    .user((user == null || user.isBlank()) ? null : user)
                    .password((password == null || password.isBlank()) ? null : password)
                    .database(databaseIndex)
                    .connectionTimeoutMillis(2000)
                    .socketTimeoutMillis(2000)
                    .ssl(tlsEnabled);
            if (tlsEnabled && trustAllCertificates) {
                clientConfigBuilder.sslSocketFactory(TlsSupport.createTrustAllSslContext().getSocketFactory());
            }
            if (tlsEnabled && !verifyHostname) {
                clientConfigBuilder.hostnameVerifier(TlsSupport.trustAllHostnameVerifier());
            }

            DefaultJedisClientConfig clientConfig = clientConfigBuilder.build();

            createdPool = new JedisPool(poolConfig, new HostAndPort(host, port), clientConfig);
            try (var jedis = createdPool.getResource()) {
                if (!"PONG".equalsIgnoreCase(jedis.ping())) {
                    throw new IllegalStateException("Redis ping check failed.");
                }
            }

            createdExecutor = BoundedExecutorFactory.create("dataprovider-redis", poolSize, queueCapacity);

            jedisPool = createdPool;
            executor = createdExecutor;
            dataAccess = new RedisDataAccess(jedisPool, executor);

            connected = true;
            logger.info(String.format(
                    "[RedisDatabase] Connected to Redis at %s:%d (DB %d, auth=%s, tls=%s), poolSize=%d, queueCapacity=%d",
                    host,
                    port,
                    databaseIndex,
                    (password != null && !password.isBlank()) ? "enabled" : "disabled",
                    tlsEnabled ? "enabled" : "disabled",
                    poolSize,
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
        if (!connected || jedisPool == null || jedisPool.isClosed()) {
            return false;
        }
        try (var jedis = jedisPool.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public KeyValueDataAccess getDataAccess() {
        return dataAccess;
    }
}
