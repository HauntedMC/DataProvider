package nl.hauntedmc.dataprovider.database.relational.impl.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.DataProviderApp;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;
import org.spongepowered.configurate.CommentedConfigurationNode;

import javax.sql.DataSource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MySQL implementation of RelationalDatabaseProvider.
 */
public class MySQLDatabase implements RelationalDatabaseProvider {

    private final CommentedConfigurationNode config;
    private HikariDataSource dataSource;
    private ExecutorService executor;
    private RelationalDataAccess dataAccess;
    private SchemaManager schemaManager;

    public MySQLDatabase(CommentedConfigurationNode config) {
        this.config = config;
    }

    @Override
    public void connect() {
        if (dataSource != null && !dataSource.isClosed()) {
            DataProviderApp.getLogger().info("[MySQLDatabase] Already connected, skipping re–initialization.");
            return;
        }

        try {
            HikariConfig hikariConfig = new HikariConfig();

            final String host = config.node("host").getString("localhost");
            final int port = config.node("port").getInt(3306);
            final String databaseName = config.node("database").getString("minecraft");
            final String user = config.node("username").getString("root");
            final String password = config.node("password").getString("");

            final String jdbcUrl = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&characterEncoding=UTF-8", host, port, databaseName);
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
            this.dataAccess = new MySQLDataAccess(dataSource, executor);
            this.schemaManager = new MySQLSchemaManager(dataSource, executor);

            DataProviderApp.getLogger().info("[MySQLDatabase] Connected successfully to " + jdbcUrl);
        } catch (Exception e) {
            DataProviderApp.getLogger().error("[MySQLDatabase] Connection failed!", e);
        }
    }

    @Override
    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            DataProviderApp.getLogger().info("[MySQLDatabase] DataSource closed.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            DataProviderApp.getLogger().info("[MySQLDatabase] ExecutorService shut down.");
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
            DataProviderApp.getLogger().error("[MySQLDatabase] Connection validation failed.", e);
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
