package nl.hauntedmc.dataprovider.database.relational.impl.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MySQL implementation of RelationalDatabaseProvider.
 */
public class MySQLDatabase implements RelationalDatabaseProvider {

    private final FileConfiguration config;
    private final Logger logger;
    private HikariDataSource dataSource;
    private ExecutorService executor;
    private RelationalDataAccess dataAccess;
    private SchemaManager schemaManager;

    public MySQLDatabase(FileConfiguration config) {
        this.config = config;
        this.logger = DataProvider.getInstance().getLogger();
    }

    @Override
    public void connect() {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.info("[MySQLDatabase] Already connected, skipping re–initialization.");
            return;
        }

        try {
            HikariConfig hikariConfig = new HikariConfig();

            final String host = config.getString("host", "localhost");
            final int port = config.getInt("port", 3306);
            final String databaseName = config.getString("database", "minecraft");
            final String user = config.getString("username", "root");
            final String password = config.getString("password", "");

            final String jdbcUrl = String.format("jdbc:mysql://%s:%d/%s?useSSL=false&characterEncoding=UTF-8", host, port, databaseName);

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
    public RelationalDataAccess getDataAccess() {
        if (dataAccess == null) {
            throw new IllegalStateException("[MySQLDatabase] DataAccess not initialized!");
        }
        return dataAccess;
    }
}
