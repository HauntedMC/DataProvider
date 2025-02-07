package nl.hauntedmc.dataprovider.database.impl.mysql;

import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.database.access.DataAccess;

import java.sql.*;
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
                setParameters(stmt, params);
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute update: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> queryForSingle(String query, Object... params) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                setParameters(stmt, params);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? mapRow(rs) : null;
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute query: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> queryForList(String query, Object... params) {
        return CompletableFuture.supplyAsync(() -> {
            List<Map<String, Object>> results = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                setParameters(stmt, params);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        results.add(mapRow(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute query: " + e.getMessage(), e);
            }
            return results;
        });
    }

    @Override
    public CompletableFuture<Object> queryForSingleValue(String query, Object... params) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                setParameters(stmt, params);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getObject(1) : null;
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute query: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> executeBatchUpdate(String query, List<Object[]> batchParams) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                for (Object[] params : batchParams) {
                    setParameters(stmt, params);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute batch update: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> beginTransaction() {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to begin transaction: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> commitTransaction() {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.commit();
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to commit transaction: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> rollbackTransaction() {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to rollback transaction: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Sets parameters for a prepared statement.
     */
    private void setParameters(PreparedStatement stmt, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            stmt.setObject(i + 1, params[i]);
        }
    }

    /**
     * Maps a row from a ResultSet to a Map.
     */
    private Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        ResultSetMetaData metaData = rs.getMetaData();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            row.put(metaData.getColumnName(i), rs.getObject(i));
        }
        return row;
    }
}
