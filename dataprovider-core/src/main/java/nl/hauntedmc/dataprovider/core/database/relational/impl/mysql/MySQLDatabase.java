package nl.hauntedmc.dataprovider.core.database.relational.impl.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.core.ManagedDatabaseProvider;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionDataSource;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** MySQL implementation of RelationalDatabaseProvider. */
public class MySQLDatabase implements RelationalDatabaseProvider, ManagedDatabaseProvider {

    static final String MYSQL_DRIVER_CLASS_NAME = "com.mysql.cj.jdbc.Driver";
    private static final Set<String> SECURE_SSL_MODES = Set.of("REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY");
    private static final AtomicInteger POOL_SEQUENCE = new AtomicInteger(1);
    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._:\\-\\[\\]]+");
    private static final Pattern DATABASE_PATTERN = Pattern.compile("[A-Za-z0-9_$.\\-]+");

    private final CommentedConfigurationNode config;
    private final LoggerAdapter logger;
    private final ExecutionHandle execution;
    private volatile HikariDataSource dataSource;
    private volatile RelationalDataAccess dataAccess;
    private volatile SchemaManager schemaManager;
    private volatile Throwable lifecycleFailure;
    private volatile int queryTimeoutSeconds;
    private volatile int defaultFetchSize;
    private volatile int connectionPoolSize;

    public MySQLDatabase(CommentedConfigurationNode config, LoggerAdapter logger) {
        this(config, logger, ExecutionHandle.direct());
    }

    public MySQLDatabase(CommentedConfigurationNode config, LoggerAdapter logger, ExecutionHandle execution) {
        this.config = Objects.requireNonNull(config, "Config cannot be null.");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
        this.execution = Objects.requireNonNull(execution, "Execution handle cannot be null.");
    }

    @Override
    public synchronized void connect() {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.info("[MySQLDatabase] Already connected, skipping re-initialization.");
            return;
        }

        HikariDataSource createdDataSource = null;
        try {
            HikariConfig hikariConfig = new HikariConfig();
            String host = requireHost(config.node("host").getString("localhost"));
            int port = requirePort(config.node("port").getInt(3306));
            String databaseName = requireDatabaseName(config.node("database").getString("minecraft"));
            String user = requireNonBlank(config.node("username").getString("root"), "username");
            String password = config.node("password").getString("");
            String sslMode = config.node("ssl_mode").getString("PREFERRED");
            String normalizedSslMode = (sslMode == null ? "PREFERRED" : sslMode).trim().toUpperCase(Locale.ROOT);
            boolean allowPublicKeyRetrieval = config.node("allow_public_key_retrieval").getBoolean(false);
            boolean requireSecureTransport = config.node("require_secure_transport").getBoolean(false);
            int poolSize = requireInRange(config.node("pool_size").getInt(10), 1, 256, "pool_size");
            int minIdle = requireInRange(config.node("min_idle").getInt(Math.min(2, poolSize)), 0, poolSize, "min_idle");
            long connectionTimeoutMs = requireInRange(config.node("connection_timeout_ms").getLong(30_000L),
                    250L, 300_000L, "connection_timeout_ms");
            long validationTimeoutMs = requireInRange(config.node("validation_timeout_ms").getLong(3_000L),
                    250L, 30_000L, "validation_timeout_ms");
            long idleTimeoutMs = requireInRange(config.node("idle_timeout_ms").getLong(600_000L),
                    10_000L, 86_400_000L, "idle_timeout_ms");
            long maxLifetimeMs = requireInRange(config.node("max_lifetime_ms").getLong(1_800_000L),
                    30_000L, 86_400_000L, "max_lifetime_ms");
            long leakDetectionThresholdMs = requireInRange(
                    config.node("leak_detection_threshold_ms").getLong(0L), 0L, 86_400_000L,
                    "leak_detection_threshold_ms");
            int connectTimeoutMs = requireInRange(config.node("connect_timeout_ms").getInt(10_000),
                    250, 300_000, "connect_timeout_ms");
            int socketTimeoutMs = requireInRange(config.node("socket_timeout_ms").getInt(10_000),
                    250, 300_000, "socket_timeout_ms");
            int queryTimeoutSeconds = requireInRange(config.node("query_timeout_seconds").getInt(0),
                    0, 3_600, "query_timeout_seconds");
            int defaultFetchSize = requireInRange(config.node("default_fetch_size").getInt(0),
                    0, 100_000, "default_fetch_size");
            boolean cachePreparedStatements = config.node("cache_prepared_statements").getBoolean(true);
            int preparedStatementCacheSize = requireInRange(
                    config.node("prepared_statement_cache_size").getInt(250), 25, 10_000,
                    "prepared_statement_cache_size");
            int preparedStatementCacheSqlLimit = requireInRange(
                    config.node("prepared_statement_cache_sql_limit").getInt(2_048), 256, 65_535,
                    "prepared_statement_cache_sql_limit");

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

            String jdbcUrl = String.format(
                    "jdbc:mysql://%s:%d/%s?characterEncoding=UTF-8&sslMode=%s&allowPublicKeyRetrieval=%s",
                    host, port, databaseName, normalizedSslMode, allowPublicKeyRetrieval);
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
            try (var connection = createdDataSource.getConnection()) {
                if (!connection.isValid(2)) {
                    throw new IllegalStateException("MySQL connection validation failed.");
                }
            }

            dataSource = createdDataSource;
            connectionPoolSize = poolSize;
            this.queryTimeoutSeconds = queryTimeoutSeconds;
            this.defaultFetchSize = defaultFetchSize;
            dataAccess = new MySQLDataAccess(dataSource, execution, queryTimeoutSeconds, defaultFetchSize);
            schemaManager = new MySQLSchemaManager(dataSource, execution);
            lifecycleFailure = null;
            logger.info(String.format(
                    "[MySQLDatabase] Connected successfully to MySQL at %s:%d (database=%s, sslMode=%s, poolSize=%d)",
                    host, port, databaseName, normalizedSslMode, poolSize));
        } catch (Exception e) {
            lifecycleFailure = e;
            if (createdDataSource != null && !createdDataSource.isClosed()) {
                createdDataSource.close();
            }
            dataAccess = null;
            schemaManager = null;
            logger.error("[MySQLDatabase] Connection failed!", e);
        }
    }

    @Override
    public synchronized void disconnect() {
        execution.close();
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("[MySQLDatabase] DataSource closed.");
        }
        dataSource = null;
        dataAccess = null;
        schemaManager = null;
    }

    @Override
    public boolean isConnected() {
        HikariDataSource snapshot = dataSource;
        return snapshot != null && !snapshot.isClosed();
    }

    @Override
    public Throwable lifecycleFailure() {
        return lifecycleFailure;
    }

    @Override
    public boolean probeRemoteHealth() {
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

    public int executionCapacity() {
        if (!isConnected() || connectionPoolSize < 1) {
            throw new IllegalStateException("[MySQLDatabase] DataSource not initialized!");
        }
        return connectionPoolSize;
    }

    /** Creates a logical provider view without creating another Hikari pool. */
    public RelationalDatabaseProvider scoped(ExecutionHandle scopedExecution) {
        HikariDataSource source = dataSource;
        if (source == null || source.isClosed()) {
            throw new IllegalStateException("[MySQLDatabase] DataSource not initialized!");
        }
        DataSource jdbcView = new ExecutionDataSource(source, scopedExecution);
        RelationalDataAccess accessView = new MySQLDataAccess(source, scopedExecution,
                queryTimeoutSeconds, defaultFetchSize);
        SchemaManager schemaView = new MySQLSchemaManager(source, scopedExecution);
        return new RelationalDatabaseProvider() {
            @Override public boolean isConnected() { return MySQLDatabase.this.isConnected() && !scopedExecution.isClosed(); }
            @Override public RelationalDataAccess getDataAccess() { return accessView; }
            @Override public DataSource getDataSource() { return jdbcView; }
            @Override public SchemaManager getSchemaManager() { return schemaView; }
        };
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
            throw new IllegalArgumentException("MySQL config 'database' contains unsupported characters: " + normalized);
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
