package nl.hauntedmc.dataprovider.database.relational.impl.mysql;

import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.TransactionCallback;
import nl.hauntedmc.dataprovider.internal.concurrent.AsyncTaskSupport;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.Objects;

/**
 * MySQL implementation of RelationalDataAccess (CRUD and query operations).
 */
public class MySQLDataAccess implements RelationalDataAccess {

    private final DataSource dataSource;
    private final ExecutorService executor;
    private final int queryTimeoutSeconds;
    private final int fetchSize;

    public MySQLDataAccess(DataSource dataSource, ExecutorService executor) {
        this(dataSource, executor, 0, 0);
    }

    public MySQLDataAccess(DataSource dataSource, ExecutorService executor, int queryTimeoutSeconds, int fetchSize) {
        this.dataSource = Objects.requireNonNull(dataSource, "Data source cannot be null.");
        this.executor = Objects.requireNonNull(executor, "Executor cannot be null.");
        this.queryTimeoutSeconds = Math.max(0, queryTimeoutSeconds);
        this.fetchSize = Math.max(0, fetchSize);
    }

    @Override
    public CompletableFuture<Void> executeUpdate(String query, Object... params) {
        final String sql = requireQuery(query);
        return AsyncTaskSupport.runAsync(executor, "mysql.executeUpdate", () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(sql)) {
                applyStatementTuning(stmt);
                setParameters(stmt, params);
                stmt.executeUpdate();
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute update", e);
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> queryForSingle(String query, Object... params) {
        final String sql = requireQuery(query);
        return AsyncTaskSupport.supplyAsync(executor, "mysql.queryForSingle", () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(sql)) {
                applyStatementTuning(stmt);
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
        });
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> queryForList(String query, Object... params) {
        final String sql = requireQuery(query);
        return AsyncTaskSupport.supplyAsync(executor, "mysql.queryForList", () -> {
            List<Map<String, Object>> result = new ArrayList<>();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(sql)) {
                applyStatementTuning(stmt);
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
        });
    }

    @Override
    public CompletableFuture<Object> queryForSingleValue(String query, Object... params) {
        final String sql = requireQuery(query);
        return AsyncTaskSupport.supplyAsync(executor, "mysql.queryForSingleValue", () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(sql)) {
                applyStatementTuning(stmt);
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
        });
    }

    @Override
    public CompletableFuture<Void> executeBatchUpdate(String query, List<Object[]> batchParams) {
        if (batchParams == null || batchParams.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        final String sql = requireQuery(query);
        return AsyncTaskSupport.runAsync(executor, "mysql.executeBatchUpdate", () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(sql)) {
                applyStatementTuning(stmt);
                boolean oldAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    for (Object[] params : batchParams) {
                        setParameters(stmt, params);
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(oldAutoCommit);
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute batch update", e);
            }
        });
    }

    @Override
    public <T> CompletableFuture<T> executeTransactionally(TransactionCallback<T> callback) {
        Objects.requireNonNull(callback, "Transaction callback cannot be null.");
        return AsyncTaskSupport.supplyAsync(executor, "mysql.executeTransactionally", () -> {
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
        });
    }

    /**
     * Executes an INSERT statement and returns the generated key.
     *
     * @param query  The INSERT SQL to execute.
     * @param params Parameters to be set in the statement.
     * @return A CompletableFuture containing the generated key.
     */
    @Override
    public CompletableFuture<Object> executeInsert(String query, Object... params) {
        final String sql = requireQuery(query);
        return AsyncTaskSupport.supplyAsync(executor, "mysql.executeInsert", () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                applyStatementTuning(stmt);
                setParameters(stmt, params);
                int affectedRows = stmt.executeUpdate();
                if (affectedRows == 0) {
                    throw new SQLException("Insert failed, no rows affected.");
                }
                try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        return generatedKeys.getObject(1);
                    } else {
                        throw new SQLException("Insert succeeded but no generated key was returned.");
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to execute insert", e);
            }
        });
    }

    private static String requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("SQL query cannot be null or blank.");
        }
        return query;
    }

    private void applyStatementTuning(PreparedStatement stmt) throws SQLException {
        if (queryTimeoutSeconds > 0) {
            stmt.setQueryTimeout(queryTimeoutSeconds);
        }
        if (fetchSize > 0) {
            stmt.setFetchSize(fetchSize);
        }
    }

    private void setParameters(PreparedStatement stmt, Object... params) throws SQLException {
        if (params == null || params.length == 0) {
            return;
        }
        for (int i = 0; i < params.length; i++) {
            stmt.setObject(i + 1, params[i]);
        }
    }

    private Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData md = rs.getMetaData();
        int columns = md.getColumnCount();
        for (int i = 1; i <= columns; i++) {
            row.put(md.getColumnLabel(i), rs.getObject(i));
        }
        return row;
    }
}
