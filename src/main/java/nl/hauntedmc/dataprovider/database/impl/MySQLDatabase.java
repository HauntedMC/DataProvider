package nl.hauntedmc.dataprovider.database.impl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.access.DataAccess;
import nl.hauntedmc.dataprovider.database.schema.SchemaManager;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.SQLException;

public class MySQLDatabase implements DatabaseProvider {
    private HikariDataSource dataSource;
    private final DataAccess dataAccess;
    private final SchemaManager schemaManager;

    public MySQLDatabase(FileConfiguration config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:mysql://" + config.getString("host") + ":" +
                config.getInt("port") + "/" + config.getString("database"));
        hikariConfig.setUsername(config.getString("username"));
        hikariConfig.setPassword(config.getString("password"));
        hikariConfig.setMaximumPoolSize(config.getInt("pool_size", 10));

        this.dataSource = new HikariDataSource(hikariConfig);
        this.dataAccess = new MySQLDataAccess(dataSource);
        this.schemaManager = new MySQLSchemaManager(dataSource);
    }

    @Override
    public void connect() {
        try (Connection conn = dataSource.getConnection()) {
            if (conn.isValid(2)) {
                System.out.println("Connected to MySQL database.");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    @Override
    public boolean isConnected() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public SchemaManager getSchemaManager() {
        return schemaManager;
    }

    @Override
    public DataAccess getDataAccess() {
        return dataAccess;
    }
}
