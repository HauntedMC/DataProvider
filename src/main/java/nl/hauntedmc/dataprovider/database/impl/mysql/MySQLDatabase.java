package nl.hauntedmc.dataprovider.database.impl.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.access.DataAccess;
import nl.hauntedmc.dataprovider.database.schema.SchemaManager;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MySQL implementation of DatabaseProvider.
 */
public class MySQLDatabase implements DatabaseProvider {

    private final FileConfiguration config;
    private final Logger logger;
    private HikariDataSource dataSource;
    private ExecutorService executor;
    private DataAccess dataAccess;
    private SchemaManager schemaManager;

    public MySQLDatabase(FileConfiguration config) {
        this.config = config;
        this.logger = DataProvider.getInstance().getLogger();
    }

    @Override
    public void connect() {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.info("[MySQLDatabase] Already connected, skipping re-initialization.");
            return;
        }

        try {
            // Build the HikariCP config
            HikariConfig hikariConfig = new HikariConfig();

            // Reading from config or environment variables
            String host = getEnvOrConfig("DB_MYSQL_HOST", config.getString("host", "localhost"));
            int port = Integer.parseInt(getEnvOrConfig("DB_MYSQL_PORT", String.valueOf(config.getInt("port", 3306))));
            String databaseName = getEnvOrConfig("DB_MYSQL_DATABASE", config.getString("database", "minecraft"));
            String user = getEnvOrConfig("DB_MYSQL_USER", config.getString("username", "root"));
            String password = getEnvOrConfig("DB_MYSQL_PASS", config.getString("password", ""));

            String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + databaseName + "?useSSL=false&characterEncoding=UTF-8";

            hikariConfig.setJdbcUrl(jdbcUrl);
            hikariConfig.setUsername(user);
            hikariConfig.setPassword(password);

            // Additional Hikari settings
            int poolSize = config.getInt("pool_size", 10);
            hikariConfig.setMaximumPoolSize(poolSize);
            hikariConfig.setConnectionTimeout(30000);
            hikariConfig.setIdleTimeout(600000);
            hikariConfig.setMaxLifetime(1800000);
            hikariConfig.setLeakDetectionThreshold(2000);

            // Initialize the connection pool
            dataSource = new HikariDataSource(hikariConfig);

            // Create an ExecutorService for DB queries
            executor = Executors.newFixedThreadPool(poolSize);

            // Initialize DataAccess and SchemaManager
            this.dataAccess = new MySQLDataAccess(dataSource, executor);
            this.schemaManager = new MySQLSchemaManager(dataSource, executor);

            logger.info("[MySQLDatabase] Connected successfully to " + jdbcUrl);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[MySQLDatabase] Connection failed!", e);
        }
    }

    @Override
    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("[MySQLDatabase] DataSource closed.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            logger.info("[MySQLDatabase] ExecutorService shut down.");
        }
    }

    @Override
    public boolean isConnected() {
        if (dataSource == null || dataSource.isClosed()) {
            return false;
        }
        try (var conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (Exception e) {
            logger.log(Level.WARNING, "[MySQLDatabase] Connection validation failed.", e);
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
    public DataAccess getDataAccess() {
        if (dataAccess == null) {
            throw new IllegalStateException("[MySQLDatabase] DataAccess not initialized!");
        }
        return dataAccess;
    }

    /**
     * Utility: returns the environment variable if present, otherwise the fallback value.
     */
    private String getEnvOrConfig(String envKey, String fallback) {
        String envValue = System.getenv(envKey);
        return (envValue != null && !envValue.isEmpty()) ? envValue : fallback;
    }
}
