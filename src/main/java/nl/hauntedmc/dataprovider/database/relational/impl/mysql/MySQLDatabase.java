package nl.hauntedmc.dataprovider.database.relational.impl.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.internal.concurrent.BoundedExecutorFactory;
import nl.hauntedmc.dataprovider.internal.ManagedDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * MySQL implementation of RelationalDatabaseProvider.
 */
public class MySQLDatabase implements RelationalDatabaseProvider, ManagedDatabaseProvider {

    static final String MYSQL_DRIVER_CLASS_NAME = "com.mysql.cj.jdbc.Driver";
    private static final Set<String> SECURE_SSL_MODES = Set.of("REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY");
    private static final AtomicInteger POOL_SEQUENCE = new AtomicInteger(1);
    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._:\\-\\[\\]]+");
    private static final Pattern DATABASE_PATTERN = Pattern.compile("[A-Za-z0-9_$.\\-]+");

    private final CommentedConfigurationNode config;
    private final ILoggerAdapter logger;
    private volatile HikariDataSource dataSource;
    private volatile ExecutorService executor;
    private volatile RelationalDataAccess dataAccess;
    private volatile SchemaManager schemaManager;

    public MySQLDatabase(CommentedConfigurationNode config, ILoggerAdapter logger) {
        this.config = config;
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    @Override
    public synchronized void connect() {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.info("[MySQLDatabase] Already connected, skipping re–initialization.");
            return;
        }

        HikariDataSource createdDataSource = null;
        ExecutorService createdExecutor = null;
        try {
            HikariConfig hikariConfig = new HikariConfig();

            final String host = requireHost(config.node("host").getString("localhost"));
            final int port = requirePort(config.node("port").getInt(3306));
            final String databaseName = requireDatabaseName(config.node("database").getString("minecraft"));
            final String user = requireNonBlank(config.node("username").getString("root"), "username");
            final String password = config.node("password").getString("");
            final String sslMode = config.node("ssl_mode").getString("PREFERRED");
            final String normalizedSslMode = (sslMode == null ? "PREFERRED" : sslMode).trim().toUpperCase(Locale.ROOT);
            final boolean allowPublicKeyRetrieval = config.node("allow_public_key_retrieval").getBoolean(false);
            final boolean requireSecureTransport = config.node("require_secure_transport").getBoolean(false);
            final int poolSize = requireInRange(config.node("pool_size").getInt(10), 1, 256, "pool_size");
            final int minIdle = requireInRange(
                    config.node("min_idle").getInt(Math.min(2, poolSize)),
                    0,
                    poolSize,
                    "min_idle"
            );
            final int queueCapacity = requireInRange(
                    config.node("queue_capacity").getInt(poolSize * 200),
                    poolSize,
                    1_000_000,
                    "queue_capacity"
            );
            final long connectionTimeoutMs = requireInRange(
                    config.node("connection_timeout_ms").getLong(30_000L),
                    250L,
                    300_000L,
                    "connection_timeout_ms"
            );
            final long validationTimeoutMs = requireInRange(
                    config.node("validation_timeout_ms").getLong(3_000L),
                    250L,
                    30_000L,
                    "validation_timeout_ms"
            );
            final long idleTimeoutMs = requireInRange(
                    config.node("idle_timeout_ms").getLong(600_000L),
                    10_000L,
                    86_400_000L,
                    "idle_timeout_ms"
            );
            final long maxLifetimeMs = requireInRange(
                    config.node("max_lifetime_ms").getLong(1_800_000L),
                    30_000L,
                    86_400_000L,
                    "max_lifetime_ms"
            );
            final long leakDetectionThresholdMs = requireInRange(
                    config.node("leak_detection_threshold_ms").getLong(0L),
                    0L,
                    86_400_000L,
                    "leak_detection_threshold_ms"
            );
            final int connectTimeoutMs = requireInRange(
                    config.node("connect_timeout_ms").getInt(10_000),
                    250,
                    300_000,
                    "connect_timeout_ms"
            );
            final int socketTimeoutMs = requireInRange(
                    config.node("socket_timeout_ms").getInt(10_000),
                    250,
                    300_000,
                    "socket_timeout_ms"
            );
            final int queryTimeoutSeconds = requireInRange(
                    config.node("query_timeout_seconds").getInt(0),
                    0,
                    3_600,
                    "query_timeout_seconds"
            );
            final int defaultFetchSize = requireInRange(
                    config.node("default_fetch_size").getInt(0),
                    0,
                    100_000,
                    "default_fetch_size"
            );
            final boolean cachePreparedStatements = config.node("cache_prepared_statements").getBoolean(true);
            final int preparedStatementCacheSize = requireInRange(
                    config.node("prepared_statement_cache_size").getInt(250),
                    25,
                    10_000,
                    "prepared_statement_cache_size"
            );
            final int preparedStatementCacheSqlLimit = requireInRange(
                    config.node("prepared_statement_cache_sql_limit").getInt(2_048),
                    256,
                    65_535,
                    "prepared_statement_cache_sql_limit"
            );

            if (requireSecureTransport && !SECURE_SSL_MODES.contains(normalizedSslMode)) {
                throw new IllegalStateException("MySQL require_secure_transport=true requires ssl_mode to be one of "
                        + SECURE_SSL_MODES + ", but got " + normalizedSslMode);
            }
            if (!SECURE_SSL_MODES.contains(normalizedSslMode)) {
                logger.warn("[MySQLDatabase] MySQL connection is not configured for strict TLS verification "
                        + "(ssl_mode=" + normalizedSslMode + ").");
            }
            if (allowPublicKeyRetrieval && !SECURE_SSL_MODES.contains(normalizedSslMode)) {
                logger.warn("[MySQLDatabase] allow_public_key_retrieval=true without strict TLS verification can expose "
                        + "credentials to MITM risk.");
            }

            final String jdbcUrl = String.format(
                    "jdbc:mysql://%s:%d/%s?characterEncoding=UTF-8&sslMode=%s&allowPublicKeyRetrieval=%s",
                    host,
                    port,
                    databaseName,
                    normalizedSslMode,
                    allowPublicKeyRetrieval
            );
            hikariConfig.setJdbcUrl(jdbcUrl);
            hikariConfig.setDriverClassName(MYSQL_DRIVER_CLASS_NAME);
            hikariConfig.setUsername(user);
            hikariConfig.setPassword(password);
            hikariConfig.setPoolName("dataprovider-mysql-" + POOL_SEQUENCE.getAndIncrement());
            hikariConfig.setMaximumPoolSize(poolSize);
            hikariConfig.setMinimumIdle(minIdle);
            hikariConfig.setConnectionTimeout(connectionTimeoutMs);
            hikariConfig.setValidationTimeout(validationTimeoutMs);
            hikariConfig.setIdleTimeout(idleTimeoutMs);
            hikariConfig.setMaxLifetime(maxLifetimeMs);
            hikariConfig.setLeakDetectionThreshold(leakDetectionThresholdMs);
            hikariConfig.addDataSourceProperty("cachePrepStmts", String.valueOf(cachePreparedStatements));
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", String.valueOf(preparedStatementCacheSize));
            hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", String.valueOf(preparedStatementCacheSqlLimit));
            hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
            hikariConfig.addDataSourceProperty("rewriteBatchedStatements", "true");
            hikariConfig.addDataSourceProperty("cacheResultSetMetadata", "true");
            hikariConfig.addDataSourceProperty("maintainTimeStats", "false");
            hikariConfig.addDataSourceProperty("tcpKeepAlive", "true");
            hikariConfig.addDataSourceProperty("connectTimeout", String.valueOf(connectTimeoutMs));
            hikariConfig.addDataSourceProperty("socketTimeout", String.valueOf(socketTimeoutMs));

            createdDataSource = createDataSource(hikariConfig);
            createdExecutor = BoundedExecutorFactory.create("dataprovider-mysql", poolSize, queueCapacity);

            try (var connection = createdDataSource.getConnection()) {
                if (!connection.isValid(2)) {
                    throw new IllegalStateException("MySQL connection validation failed.");
                }
            }

            dataSource = createdDataSource;
            executor = createdExecutor;
            this.dataAccess = new MySQLDataAccess(dataSource, executor, queryTimeoutSeconds, defaultFetchSize);
            this.schemaManager = new MySQLSchemaManager(dataSource, executor);

            logger.info(String.format(
                    "[MySQLDatabase] Connected successfully to MySQL at %s:%d (database=%s, sslMode=%s, poolSize=%d, queueCapacity=%d)",
                    host,
                    port,
                    databaseName,
                    normalizedSslMode,
                    poolSize,
                    queueCapacity
            ));
        } catch (Exception e) {
            if (createdExecutor != null) {
                createdExecutor.shutdownNow();
            }
            if (createdDataSource != null && !createdDataSource.isClosed()) {
                createdDataSource.close();
            }
            this.dataAccess = null;
            this.schemaManager = null;
            logger.error("[MySQLDatabase] Connection failed!", e);
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
            logger.info("[MySQLDatabase] ExecutorService shut down.");
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("[MySQLDatabase] DataSource closed.");
        }
        executor = null;
        dataSource = null;
        dataAccess = null;
        schemaManager = null;
    }

    @Override
    public boolean isConnected() {
        HikariDataSource snapshot = dataSource;
        if (snapshot == null || snapshot.isClosed()) {
            return false;
        }
        try (var conn = snapshot.getConnection()) {
            return conn.isValid(2);
        } catch (Exception e) {
            logger.error("[MySQLDatabase] Connection validation failed.", e);
            return false;
        }
    }

    @Override
    public SchemaManager getSchemaManager() {
        if (schemaManager == null) {
            throw new IllegalStateException("[MySQLDatabase] SchemaManager not initialized!");
        }
        return schemaManager;
    }

    @Override
    public RelationalDataAccess getDataAccess() {
        if (dataAccess == null) {
            throw new IllegalStateException("[MySQLDatabase] DataAccess not initialized!");
        }
        return dataAccess;
    }

    @Override
    public DataSource getDataSource() {
        return dataSource;
    }

    HikariDataSource createDataSource(HikariConfig hikariConfig) {
        return new HikariDataSource(hikariConfig);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MySQL config '" + fieldName + "' cannot be null or blank.");
        }
        return value.trim();
    }

    private static String requireHost(String host) {
        String normalized = requireNonBlank(host, "host");
        if (!HOST_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("MySQL config 'host' contains unsupported characters: " + normalized);
        }
        return normalized;
    }

    private static String requireDatabaseName(String databaseName) {
        String normalized = requireNonBlank(databaseName, "database");
        if (!DATABASE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "MySQL config 'database' contains unsupported characters: " + normalized
            );
        }
        return normalized;
    }

    private static int requirePort(int port) {
        return requireInRange(port, 1, 65_535, "port");
    }

    private static int requireInRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("MySQL config '" + fieldName + "' must be between " + min + " and " + max
                    + ", but got " + value + ".");
        }
        return value;
    }

    private static long requireInRange(long value, long min, long max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("MySQL config '" + fieldName + "' must be between " + min + " and " + max
                    + ", but got " + value + ".");
        }
        return value;
    }
}
