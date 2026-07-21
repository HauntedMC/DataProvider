package nl.hauntedmc.dataprovider.core.database.relational.impl.mysql;

import nl.hauntedmc.dataprovider.core.concurrent.AsyncTaskSupport;
import nl.hauntedmc.dataprovider.core.exception.DataProviderExceptionMapper;
import nl.hauntedmc.dataprovider.database.relational.RelationalDataAccess;
import nl.hauntedmc.dataprovider.database.relational.TransactionCallback;
import nl.hauntedmc.dataprovider.exception.DataTransactionException;
import nl.hauntedmc.dataprovider.exception.ExecutionOutcome;
import nl.hauntedmc.dataprovider.exception.TransactionPhase;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** MySQL implementation of RelationalDataAccess. */
public class MySQLDataAccess implements RelationalDataAccess {

    private static final String TRANSACTION_OPERATION = "mysql.executeTransactionally";

    private final DataSource dataSource;
    private final Executor executor;
    private final int queryTimeoutSeconds;
    private final int fetchSize;

    public MySQLDataAccess(DataSource dataSource, Executor executor) {
        this(dataSource, executor, 0, 0);
    }

    public MySQLDataAccess(DataSource dataSource, Executor executor, int queryTimeoutSeconds, int fetchSize) {
        this.dataSource = Objects.requireNonNull(dataSource, "Data source cannot be null.");
        this.executor = Objects.requireNonNull(executor, "Executor cannot be null.");
        this.queryTimeoutSeconds = Math.max(0, queryTimeoutSeconds);
        this.fetchSize = Math.max(0, fetchSize);
    }

    @Override
    public CompletableFuture<Void> executeUpdate(String query, Object... params) {
        String sql = requireQuery(query);
        return AsyncTaskSupport.runAsync(executor, "mysql.executeUpdate", () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(sql)) {
                applyStatementTuning(stmt);
                setParameters(stmt, params);
                stmt.executeUpdate();
            }
        });
    }

    @Override
    public CompletableFuture<Map<String, Object>> queryForSingle(String query, Object... params) {
        String sql = requireQuery(query);
        return AsyncTaskSupport.supplyAsync(executor, "mysql.queryForSingle", () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(sql)) {
                applyStatementTuning(stmt);
                setParameters(stmt, params);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? mapRow(rs) : null;
                }
            }
        });
    }

    @Override
    public CompletableFuture<List<Map<String, Object>>> queryForList(String query, Object... params) {
        String sql = requireQuery(query);
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
                return result;
            }
        });
    }

    @Override
    public CompletableFuture<Object> queryForSingleValue(String query, Object... params) {
        String sql = requireQuery(query);
        return AsyncTaskSupport.supplyAsync(executor, "mysql.queryForSingleValue", () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(sql)) {
                applyStatementTuning(stmt);
                setParameters(stmt, params);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? rs.getObject(1) : null;
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> executeBatchUpdate(String query, List<Object[]> batchParams) {
        if (batchParams == null || batchParams.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        String sql = requireQuery(query);
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
                } catch (SQLException primary) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        primary.addSuppressed(rollbackFailure);
                    }
                    throw primary;
                } finally {
                    connection.setAutoCommit(oldAutoCommit);
                }
            }
        });
    }

    @Override
    public <T> CompletableFuture<T> executeTransactionally(TransactionCallback<T> callback) {
        Objects.requireNonNull(callback, "Transaction callback cannot be null.");
        return AsyncTaskSupport.supplyAsync(executor, TRANSACTION_OPERATION, () -> executeTransaction(callback));
    }

    private <T> T executeTransaction(TransactionCallback<T> callback) {
        Connection connection;
        boolean oldAutoCommit;
        try {
            connection = dataSource.getConnection();
            oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
        } catch (Throwable beginFailure) {
            throw DataProviderExceptionMapper.transactionFailure(
                    beginFailure, executor, TRANSACTION_OPERATION, TransactionPhase.BEGIN,
                    ExecutionOutcome.NOT_STARTED);
        }

        try (connection) {
            T result;
            try {
                result = callback.doInTransaction(connection);
            } catch (Throwable callbackFailure) {
                DataTransactionException structured = rollbackAfterFailure(
                        connection, callbackFailure, TransactionPhase.CALLBACK, ExecutionOutcome.NOT_APPLIED);
                restoreAutoCommit(connection, oldAutoCommit, structured);
                throw structured;
            }

            try {
                connection.commit();
            } catch (Throwable commitFailure) {
                DataTransactionException structured = rollbackAfterFailure(
                        connection, commitFailure, TransactionPhase.COMMIT, ExecutionOutcome.MAY_HAVE_APPLIED);
                restoreAutoCommit(connection, oldAutoCommit, structured);
                throw structured;
            }

            try {
                connection.setAutoCommit(oldAutoCommit);
            } catch (Throwable restoreFailure) {
                throw DataProviderExceptionMapper.transactionFailure(
                        restoreFailure, executor, TRANSACTION_OPERATION, TransactionPhase.ROLLBACK,
                        ExecutionOutcome.UNKNOWN);
            }
            return result;
        } catch (DataTransactionException structured) {
            throw structured;
        } catch (Throwable closeFailure) {
            throw DataProviderExceptionMapper.transactionFailure(
                    closeFailure, executor, TRANSACTION_OPERATION, TransactionPhase.ROLLBACK,
                    ExecutionOutcome.UNKNOWN);
        }
    }

    private DataTransactionException rollbackAfterFailure(
            Connection connection,
            Throwable primary,
            TransactionPhase phase,
            ExecutionOutcome outcome
    ) {
        DataTransactionException structured = DataProviderExceptionMapper.transactionFailure(
                primary, executor, TRANSACTION_OPERATION, phase, outcome);
        try {
            connection.rollback();
        } catch (Throwable rollbackFailure) {
            structured.addSuppressed(DataProviderExceptionMapper.transactionFailure(
                    rollbackFailure, executor, TRANSACTION_OPERATION, TransactionPhase.ROLLBACK,
                    ExecutionOutcome.UNKNOWN));
        }
        return structured;
    }

    private void restoreAutoCommit(
            Connection connection,
            boolean oldAutoCommit,
            DataTransactionException primary
    ) {
        try {
            connection.setAutoCommit(oldAutoCommit);
        } catch (Throwable restoreFailure) {
            primary.addSuppressed(DataProviderExceptionMapper.transactionFailure(
                    restoreFailure, executor, TRANSACTION_OPERATION, TransactionPhase.ROLLBACK,
                    ExecutionOutcome.UNKNOWN));
        }
    }

    @Override
    public CompletableFuture<Object> executeInsert(String query, Object... params) {
        String sql = requireQuery(query);
        return AsyncTaskSupport.supplyAsync(executor, "mysql.executeInsert", () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                applyStatementTuning(stmt);
                setParameters(stmt, params);
                if (stmt.executeUpdate() == 0) {
                    throw new SQLException("Insert failed, no rows affected.");
                }
                try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        return generatedKeys.getObject(1);
                    }
                    throw new SQLException("Insert succeeded but no generated key was returned.");
                }
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

    private static void setParameters(PreparedStatement stmt, Object... params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int index = 0; index < params.length; index++) {
            stmt.setObject(index + 1, params[index]);
        }
    }

    private static Map<String, Object> mapRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        ResultSetMetaData metadata = rs.getMetaData();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            row.put(metadata.getColumnLabel(index), rs.getObject(index));
        }
        return row;
    }
}
