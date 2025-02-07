package nl.hauntedmc.dataprovider.database.impl.mysql;

import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.database.access.DataAccess;
import nl.hauntedmc.dataprovider.database.access.TransactionCallback;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * MySQL implementation of DataAccess (CRUD/Queries).
 */
public class MySQLDataAccess implements DataAccess {

    private final HikariDataSource dataSource;
    private final ExecutorService executor;

    public MySQLDataAccess(HikariDataSource dataSource, ExecutorService executor) {
        this.dataSource = dataSource;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Void> executeUpdate(String query, Object... params) {
        return CompletableFuture.runAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                setParameters(stmt, params);
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute update", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Map<String, Object>> queryForSingle(String query, Object... params) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                setParameters(stmt, params);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return mapRow(rs);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute queryForSingle", e);
            }
            return null;
        }, executor);
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> queryForList(String query, Object... params) {
        return CompletableFuture.supplyAsync(() -> {
            List<Map<String, Object>> result = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                setParameters(stmt, params);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        result.add(mapRow(rs));
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute queryForList", e);
            }
            return result;
        }, executor);
    }

    @Override
    public CompletableFuture<Object> queryForSingleValue(String query, Object... params) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(query)) {
                setParameters(stmt, params);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getObject(1);
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute queryForSingleValue", e);
            }
            return null;
        }, executor);
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
                throw new RuntimeException("Failed to execute batch update", e);
            }
        }, executor);
    }

    @Override
    public <T> CompletableFuture<T> executeTransactionally(TransactionCallback<T> callback) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                boolean oldAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    T result = callback.doInTransaction(connection);
                    connection.commit();
                    return result;
                } catch (Exception e) {
                    connection.rollback();
                    throw new RuntimeException("Transaction failed, rolled back.", e);
                } finally {
                    connection.setAutoCommit(oldAutoCommit);
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute transactionally", e);
            }
        }, executor);
    }

    /**
     * Helper to set parameters on PreparedStatement.
     */
    private void setParameters(PreparedStatement stmt, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            stmt.setObject(i + 1, params[i]);
        }
    }

    /**
     * Helper to map a ResultSet row to a Map.
     */
    private Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData md = rs.getMetaData();
        int columns = md.getColumnCount();
        for (int i = 1; i <= columns; i++) {
            row.put(md.getColumnName(i), rs.getObject(i));
        }
        return row;
    }
}
