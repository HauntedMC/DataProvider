package nl.hauntedmc.dataprovider.database.impl;

import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.database.access.DataAccess;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class MySQLDataAccess implements DataAccess {
    private final HikariDataSource dataSource;

    public MySQLDataAccess(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public CompletableFuture<Void> executeUpdate(String query, Object... params) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                for (int i = 0; i < params.length; i++) {
                    stmt.setObject(i + 1, params[i]);
                }
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute update: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> queryForList(String query, Object... params) {
        return CompletableFuture.supplyAsync(() -> {
            List<Map<String, Object>> results = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                for (int i = 0; i < params.length; i++) {
                    stmt.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = stmt.executeQuery()) {
                    int columnCount = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> row = new HashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            row.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
                        }
                        results.add(row);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute query: " + e.getMessage(), e);
            }
            return results;
        });
    }
}
