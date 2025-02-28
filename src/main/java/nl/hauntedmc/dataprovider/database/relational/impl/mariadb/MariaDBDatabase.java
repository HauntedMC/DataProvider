package nl.hauntedmc.dataprovider.database.relational.impl.mariadb;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;
import org.spongepowered.configurate.CommentedConfigurationNode;

import javax.sql.DataSource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MariaDB implementation of RelationalDatabaseProvider.
 * This version uses Configurate to load configuration from a YAML file.
 */
public class MariaDBDatabase implements RelationalDatabaseProvider {

    private final CommentedConfigurationNode config;
    private HikariDataSource dataSource;
    private ExecutorService executor;
    private RelationalDataAccess dataAccess;
    private SchemaManager schemaManager;

    public MariaDBDatabase(CommentedConfigurationNode config) {
        this.config = config;
    }

    @Override
    public void connect() {
        if (dataSource != null && !dataSource.isClosed()) {
            DataProvider.getLogger().info("[MariaDBDatabase] Already connected, skipping re–initialization.");
            return;
        }
        try {
            HikariConfig hikariConfig = new HikariConfig();

            final String host = config.node("host").getString("localhost");
            final int port = config.node("port").getInt(3306);
            final String databaseName = config.node("database").getString("minecraft");
            final String user = config.node("username").getString("root");
            final String password = config.node("password").getString("");

            // Use the MariaDB JDBC URL format.
            final String jdbcUrl = String.format("jdbc:mariadb://%s:%d/%s?useSSL=false&characterEncoding=UTF-8", host, port, databaseName);

            hikariConfig.setJdbcUrl(jdbcUrl);
            hikariConfig.setUsername(user);
            hikariConfig.setPassword(password);

            final int poolSize = config.node("pool_size").getInt(10);
            hikariConfig.setMaximumPoolSize(poolSize);
            hikariConfig.setConnectionTimeout(30000);
            hikariConfig.setIdleTimeout(600000);
            hikariConfig.setMaxLifetime(1800000);
            hikariConfig.setLeakDetectionThreshold(2000);

            dataSource = new HikariDataSource(hikariConfig);
            executor = Executors.newFixedThreadPool(poolSize);
            this.dataAccess = new MariaDBDataAccess(dataSource, executor);
            this.schemaManager = new MariaDBSchemaManager(dataSource, executor);

            DataProvider.getLogger().info("[MariaDBDatabase] Connected successfully to " + jdbcUrl);
        } catch (Exception e) {
            DataProvider.getLogger().error("[MariaDBDatabase] Connection failed!", e);
        }
    }

    @Override
    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            DataProvider.getLogger().info("[MariaDBDatabase] DataSource closed.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            DataProvider.getLogger().info("[MariaDBDatabase] ExecutorService shut down.");
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
            DataProvider.getLogger().warn("[MariaDBDatabase] Connection validation failed: " + e.getMessage());
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
