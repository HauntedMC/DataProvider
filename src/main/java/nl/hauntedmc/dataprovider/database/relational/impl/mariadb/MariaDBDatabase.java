package nl.hauntedmc.dataprovider.database.relational.impl.mariadb;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;
import nl.hauntedmc.dataprovider.logging.DPLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * MariaDB implementation of RelationalDatabaseProvider.
 */
public class MariaDBDatabase implements RelationalDatabaseProvider {

    private final ConfigurationSection config;
    private HikariDataSource dataSource;
    private ExecutorService executor;
    private RelationalDataAccess dataAccess;
    private SchemaManager schemaManager;

    public MariaDBDatabase(ConfigurationSection config) {
        this.config = config;
    }

    @Override
    public void connect() {
        if (dataSource != null && !dataSource.isClosed()) {
            DPLogger.info("[MariaDBDatabase] Already connected, skipping re–initialization.");
            return;
        }
        try {
            HikariConfig hikariConfig = new HikariConfig();

            final String host = config.getString("host", "localhost");
            final int port = config.getInt("port", 3306);
            final String databaseName = config.getString("database", "minecraft");
            final String user = config.getString("username", "root");
            final String password = config.getString("password", "");

            // Use the MariaDB JDBC URL format.
            final String jdbcUrl = String.format("jdbc:mariadb://%s:%d/%s?useSSL=false&characterEncoding=UTF-8", host, port, databaseName);

            hikariConfig.setJdbcUrl(jdbcUrl);
            hikariConfig.setUsername(user);
            hikariConfig.setPassword(password);

            final int poolSize = config.getInt("pool_size", 10);
            hikariConfig.setMaximumPoolSize(poolSize);
            hikariConfig.setConnectionTimeout(30000);
            hikariConfig.setIdleTimeout(600000);
            hikariConfig.setMaxLifetime(1800000);
            hikariConfig.setLeakDetectionThreshold(2000);

            dataSource = new HikariDataSource(hikariConfig);
            executor = Executors.newFixedThreadPool(poolSize);
            this.dataAccess = new MariaDBDataAccess(dataSource, executor);
            this.schemaManager = new MariaDBSchemaManager(dataSource, executor);

            DPLogger.info("[MariaDBDatabase] Connected successfully to " + jdbcUrl);
        } catch (Exception e) {
            DPLogger.error("[MariaDBDatabase] Connection failed!", e);
        }
    }

    @Override
    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            DPLogger.info("[MariaDBDatabase] DataSource closed.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            DPLogger.info("[MariaDBDatabase] ExecutorService shut down.");
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
            DPLogger.warning("[MariaDBDatabase] Connection validation failed: " + e.getMessage());
            return false;
        }
    }

    @Override
    public SchemaManager getSchemaManager() {
        if (schemaManager == null) {
            throw new IllegalStateException("[MariaDBDatabase] SchemaManager not initialized!");
        }
        return schemaManager;
    }

    @Override
    public RelationalDataAccess getDataAccess() {
        if (dataAccess == null) {
            throw new IllegalStateException("[MariaDBDatabase] DataAccess not initialized!");
        }
        return dataAccess;
    }

    @Override
    public DataSource getDataSource() {
        return dataSource;
    }
}
