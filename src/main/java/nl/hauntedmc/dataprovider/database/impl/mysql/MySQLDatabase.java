package nl.hauntedmc.dataprovider.database.impl.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.access.DataAccess;
import nl.hauntedmc.dataprovider.database.schema.SchemaManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MySQLDatabase implements DatabaseProvider {
    private HikariDataSource dataSource;
    private DataAccess dataAccess;
    private SchemaManager schemaManager;
    private final FileConfiguration config;
    private final Logger logger;

    public MySQLDatabase(FileConfiguration config) {
        this.config = config;
        this.logger = DataProvider.getInstance().getLogger();
    }

    @Override
    public void connect() {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.info("MySQL is already connected.");
            return;
        }

        try {
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl("jdbc:mysql://" + config.getString("host") + ":" +
                    config.getInt("port") + "/" + config.getString("database"));
            hikariConfig.setUsername(config.getString("username"));
            hikariConfig.setPassword(config.getString("password"));
            hikariConfig.setMaximumPoolSize(config.getInt("pool_size", 10));
            hikariConfig.setConnectionTimeout(30000);
            hikariConfig.setIdleTimeout(600000);
            hikariConfig.setMaxLifetime(1800000);
            hikariConfig.setLeakDetectionThreshold(2000); // Detects connection leaks

            this.dataSource = new HikariDataSource(hikariConfig);

            // Initialize only after successful connection
            this.dataAccess = new MySQLDataAccess(dataSource);
            this.schemaManager = new MySQLSchemaManager(dataSource);

            logger.info("Connected to MySQL database successfully.");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to connect to MySQL database!", e);
        }
    }

    @Override
    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("Disconnected from MySQL database.");
        } else {
            logger.warning("Attempted to disconnect, but MySQL was already closed.");
        }
    }

    @Override
    public boolean isConnected() {
        try {
            if (dataSource != null && !dataSource.isClosed()) {
                try (Connection conn = dataSource.getConnection()) {
                    return conn.isValid(2);
                }
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Database connection check failed.", e);
        }
        return false;
    }

    @Override
    public SchemaManager getSchemaManager() {
        if (schemaManager == null) {
            throw new IllegalStateException("SchemaManager is not initialized. Is MySQL connected?");
        }
        return schemaManager;
    }

    @Override
    public DataAccess getDataAccess() {
        if (dataAccess == null) {
            throw new IllegalStateException("DataAccess is not initialized. Is MySQL connected?");
        }
        return dataAccess;
    }
}
