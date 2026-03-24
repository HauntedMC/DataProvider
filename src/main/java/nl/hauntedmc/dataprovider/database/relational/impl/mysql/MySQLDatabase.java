package nl.hauntedmc.dataprovider.database.relational.impl.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * MySQL implementation of RelationalDatabaseProvider.
 */
public class MySQLDatabase implements RelationalDatabaseProvider {

    private static final Set<String> SECURE_SSL_MODES = Set.of("REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY");

    private final CommentedConfigurationNode config;
    private final ILoggerAdapter logger;
    private HikariDataSource dataSource;
    private ExecutorService executor;
    private RelationalDataAccess dataAccess;
    private SchemaManager schemaManager;

    public MySQLDatabase(CommentedConfigurationNode config, ILoggerAdapter logger) {
        this.config = config;
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    @Override
    public void connect() {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.info("[MySQLDatabase] Already connected, skipping re–initialization.");
            return;
        }

        HikariDataSource createdDataSource = null;
        ExecutorService createdExecutor = null;
        try {
            HikariConfig hikariConfig = new HikariConfig();

            final String host = config.node("host").getString("localhost");
            final int port = config.node("port").getInt(3306);
            final String databaseName = config.node("database").getString("minecraft");
            final String user = config.node("username").getString("root");
            final String password = config.node("password").getString("");
            final String sslMode = config.node("ssl_mode").getString("PREFERRED");
            final String normalizedSslMode = (sslMode == null ? "PREFERRED" : sslMode).trim().toUpperCase(Locale.ROOT);
            final boolean allowPublicKeyRetrieval = config.node("allow_public_key_retrieval").getBoolean(false);
            final boolean requireSecureTransport = config.node("require_secure_transport").getBoolean(false);

            if (requireSecureTransport && !SECURE_SSL_MODES.contains(normalizedSslMode)) {
                throw new IllegalStateException("MySQL require_secure_transport=true requires ssl_mode to be one of "
                        + SECURE_SSL_MODES + ", but got " + normalizedSslMode);
            }
            if (!SECURE_SSL_MODES.contains(normalizedSslMode)) {
                logger.warn("[MySQLDatabase] MySQL connection is not configured for strict TLS verification "
                        + "(ssl_mode=" + normalizedSslMode + ").");
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
            hikariConfig.setUsername(user);
            hikariConfig.setPassword(password);

            final int poolSize = Math.max(1, config.node("pool_size").getInt(10));
            hikariConfig.setMaximumPoolSize(poolSize);
            hikariConfig.setConnectionTimeout(30000);
            hikariConfig.setIdleTimeout(600000);
            hikariConfig.setMaxLifetime(1800000);
            hikariConfig.setLeakDetectionThreshold(2000);

            createdDataSource = new HikariDataSource(hikariConfig);
            createdExecutor = Executors.newFixedThreadPool(poolSize);

            try (var connection = createdDataSource.getConnection()) {
                if (!connection.isValid(2)) {
                    throw new IllegalStateException("MySQL connection validation failed.");
                }
            }

            dataSource = createdDataSource;
            executor = createdExecutor;
            this.dataAccess = new MySQLDataAccess(dataSource, executor);
            this.schemaManager = new MySQLSchemaManager(dataSource, executor);

            logger.info(String.format(
                    "[MySQLDatabase] Connected successfully to MySQL at %s:%d (database=%s, sslMode=%s)",
                    host,
                    port,
                    databaseName,
                    normalizedSslMode
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
        if (dataSource == null || dataSource.isClosed()) {
            return false;
        }
        try (var conn = dataSource.getConnection()) {
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
}
