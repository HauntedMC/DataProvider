package nl.hauntedmc.dataprovider.core.database.relational.impl.mysql;

import nl.hauntedmc.dataprovider.core.testutil.DirectExecutorService;
import nl.hauntedmc.dataprovider.exception.BackendUnavailableException;
import nl.hauntedmc.dataprovider.exception.DataProviderOperationException;
import nl.hauntedmc.dataprovider.exception.DataTransactionException;
import nl.hauntedmc.dataprovider.exception.ExecutionOutcome;
import nl.hauntedmc.dataprovider.exception.TransactionPhase;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MySQLDataAccessTest {

    @Test
    void executeUpdateSetsParametersAndRunsStatement() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("UPDATE players SET score=? WHERE id=?")).thenReturn(statement);

        MySQLDataAccess access = new MySQLDataAccess(dataSource, new DirectExecutorService());
        access.executeUpdate("UPDATE players SET score=? WHERE id=?", 10, 5L).join();

        verify(statement).setObject(1, 10);
        verify(statement).setObject(2, 5L);
        verify(statement).executeUpdate();
    }

    @Test
    void queryForSingleMapsFirstRow() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(2);
        when(metaData.getColumnLabel(1)).thenReturn("id");
        when(metaData.getColumnLabel(2)).thenReturn("name");
        when(resultSet.getObject(1)).thenReturn(7);
        when(resultSet.getObject(2)).thenReturn("Remy");

        Map<String, Object> row = new MySQLDataAccess(dataSource, new DirectExecutorService())
                .queryForSingle("SELECT * FROM players WHERE id=?", 7).join();
        assertEquals(7, row.get("id"));
        assertEquals("Remy", row.get("name"));
    }

    @Test
    void queryForSingleReturnsNullWhenNoRowsFound() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);
        assertNull(new MySQLDataAccess(dataSource, new DirectExecutorService())
                .queryForSingle("SELECT 1").join());
    }

    @Test
    void queryForListMapsAllRows() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(2);
        when(metaData.getColumnLabel(1)).thenReturn("id");
        when(metaData.getColumnLabel(2)).thenReturn("name");
        when(resultSet.getObject(1)).thenReturn(1, 2);
        when(resultSet.getObject(2)).thenReturn("a", "b");

        List<Map<String, Object>> rows = new MySQLDataAccess(dataSource, new DirectExecutorService())
                .queryForList("SELECT * FROM players").join();
        assertEquals(2, rows.size());
        assertEquals("b", rows.get(1).get("name"));
    }

    @Test
    void queryForSingleValueReturnsFirstColumnAndHandlesEmptyResult() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn("value");

        MySQLDataAccess access = new MySQLDataAccess(dataSource, new DirectExecutorService());
        assertEquals("value", access.queryForSingleValue("SELECT value FROM test").join());
        when(resultSet.next()).thenReturn(false);
        assertNull(access.queryForSingleValue("SELECT value FROM test WHERE id=999").join());
    }

    @Test
    void executeBatchUpdateAddsEachBatchEntry() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        new MySQLDataAccess(dataSource, new DirectExecutorService()).executeBatchUpdate(
                "INSERT INTO test(a,b) VALUES (?,?)",
                List.of(new Object[]{1, "x"}, new Object[]{2, "y"})
        ).join();

        verify(statement).setObject(1, 1);
        verify(statement).setObject(2, "x");
        verify(statement).setObject(1, 2);
        verify(statement).setObject(2, "y");
        verify(statement).executeBatch();
    }

    @Test
    void executeTransactionallyCommitsRestoresAndClosesConnection() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);

        String result = new MySQLDataAccess(dataSource, new DirectExecutorService())
                .executeTransactionally(conn -> "done").join();
        assertEquals("done", result);
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection).setAutoCommit(true);
        verify(connection).close();
    }

    @Test
    void transactionConnectionExpiresImmediatelyAfterCallback() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        AtomicReference<Connection> retained = new AtomicReference<>();
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.getMetaData()).thenReturn(metadata);

        new MySQLDataAccess(dataSource, new DirectExecutorService())
                .executeTransactionally(callbackConnection -> {
                    retained.set(callbackConnection);
                    assertNotSame(connection, callbackConnection.createStatement().getConnection());
                    assertNotSame(connection, callbackConnection.getMetaData().getConnection());
                    return "done";
                }).join();

        assertTrue(retained.get().isClosed());
        assertThrows(SQLException.class, retained.get()::createStatement);
        verify(connection).commit();
        verify(connection).close();
    }

    @Test
    void transactionCallbackCannotControlConnectionLifecycleOrUnwrapThePoolConnection() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);

        new MySQLDataAccess(dataSource, new DirectExecutorService())
                .executeTransactionally(callbackConnection -> {
                    assertThrows(SQLException.class, callbackConnection::close);
                    assertThrows(SQLException.class, callbackConnection::close);
                    assertThrows(SQLException.class, () -> callbackConnection.abort(Runnable::run));
                    assertThrows(SQLException.class, callbackConnection::commit);
                    assertThrows(SQLException.class, callbackConnection::rollback);
                    assertThrows(SQLException.class, () -> callbackConnection.setAutoCommit(true));
                    assertNotSame(connection, callbackConnection.unwrap(Connection.class));
                    assertThrows(SQLException.class, () -> callbackConnection.unwrap(java.sql.SQLXML.class));
                    return null;
                }).join();

        verify(connection, times(1)).commit();
        verify(connection, times(1)).close();
    }

    @Test
    void transactionRestoresMutableConnectionPropertiesBeforePoolReturn() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Map<String, Class<?>> initialTypeMap = new HashMap<>();
        initialTypeMap.put("initial", String.class);
        Properties initialClientInfo = new Properties();
        initialClientInfo.setProperty("applicationName", "initial");
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.isReadOnly()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_SERIALIZABLE);
        when(connection.getCatalog()).thenReturn("initial_catalog");
        when(connection.getTypeMap()).thenReturn(initialTypeMap);
        when(connection.getHoldability()).thenReturn(ResultSet.HOLD_CURSORS_OVER_COMMIT);
        when(connection.getSchema()).thenReturn("initial_schema");
        when(connection.getNetworkTimeout()).thenReturn(123);
        when(connection.getClientInfo()).thenReturn(initialClientInfo);

        new MySQLDataAccess(dataSource, new DirectExecutorService())
                .executeTransactionally(callbackConnection -> {
                    callbackConnection.setReadOnly(false);
                    callbackConnection.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
                    callbackConnection.setCatalog("callback_catalog");
                    callbackConnection.setTypeMap(Map.of("callback", Integer.class));
                    callbackConnection.setHoldability(ResultSet.CLOSE_CURSORS_AT_COMMIT);
                    callbackConnection.setSchema("callback_schema");
                    callbackConnection.setNetworkTimeout(Runnable::run, 456);
                    callbackConnection.setClientInfo("applicationName", "callback");
                    return null;
                }).join();

        verify(connection).setReadOnly(true);
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        verify(connection).setCatalog("initial_catalog");
        verify(connection).setTypeMap(initialTypeMap);
        verify(connection).setHoldability(ResultSet.HOLD_CURSORS_OVER_COMMIT);
        verify(connection).setSchema("initial_schema");
        verify(connection).setNetworkTimeout(any(Executor.class), eq(123));
        verify(connection).setClientInfo("applicationName", "initial");
        verify(connection).setAutoCommit(true);
    }

    @Test
    void transactionAttemptsAllPropertyRestorationWhenOneRestoreFails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.isReadOnly()).thenReturn(true);
        when(connection.getSchema()).thenReturn("initial_schema");
        doThrow(new SQLException("read-only restore failed")).when(connection).setReadOnly(true);

        CompletionException completion = assertThrows(CompletionException.class,
                () -> new MySQLDataAccess(dataSource, new DirectExecutorService())
                        .executeTransactionally(callbackConnection -> {
                            callbackConnection.setReadOnly(false);
                            callbackConnection.setSchema("callback_schema");
                            return null;
                        }).join());

        DataTransactionException transaction = assertInstanceOf(
                DataTransactionException.class, completion.getCause());
        assertEquals(TransactionPhase.CLEANUP, transaction.phase());
        verify(connection).setSchema("initial_schema");
        verify(connection).setAutoCommit(true);
        verify(connection).close();
    }

    @Test
    void transactionFailsClosedWhenDriverCannotExposeMutableState() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        AtomicBoolean callbackCalled = new AtomicBoolean();
        when(dataSource.getConnection()).thenReturn(connection);
        doThrow(new SQLFeatureNotSupportedException("schema unavailable")).when(connection).getSchema();

        CompletionException completion = assertThrows(CompletionException.class,
                () -> new MySQLDataAccess(dataSource, new DirectExecutorService())
                        .executeTransactionally(callbackConnection -> {
                            callbackCalled.set(true);
                            return null;
                        }).join());

        DataTransactionException transaction = assertInstanceOf(
                DataTransactionException.class, completion.getCause());
        assertEquals(TransactionPhase.BEGIN, transaction.phase());
        assertFalse(callbackCalled.get());
        verify(connection).close();
    }

    @Test
    void failedTransactionSetupClosesAcquiredConnection() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        doThrow(new SQLException("setup secret")).when(connection).setAutoCommit(false);

        CompletionException completion = assertThrows(CompletionException.class,
                () -> new MySQLDataAccess(dataSource, new DirectExecutorService())
                        .executeTransactionally(conn -> "unused").join());
        DataTransactionException transaction = assertInstanceOf(
                DataTransactionException.class, completion.getCause());
        assertEquals(TransactionPhase.BEGIN, transaction.phase());
        assertEquals(ExecutionOutcome.NOT_STARTED, transaction.executionOutcome());
        verify(connection).close();
    }

    @Test
    void callbackRollbackAndCloseFailuresRemainStructuredAndRedacted() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(false);
        doThrow(new SQLException("rollback secret")).when(connection).rollback();
        doThrow(new SQLException("close secret")).when(connection).close();

        CompletionException completion = assertThrows(CompletionException.class,
                () -> new MySQLDataAccess(dataSource, new DirectExecutorService())
                        .executeTransactionally(conn -> {
                            throw new IllegalStateException("callback secret");
                        }).join());
        DataTransactionException transaction = assertInstanceOf(
                DataTransactionException.class, completion.getCause());
        assertEquals(TransactionPhase.CALLBACK, transaction.phase());
        assertEquals(ExecutionOutcome.NOT_APPLIED, transaction.executionOutcome());
        assertEquals(2, transaction.getSuppressed().length);
        for (Throwable suppressed : transaction.getSuppressed()) {
            DataTransactionException structured = assertInstanceOf(DataTransactionException.class, suppressed);
            assertFalse(structured.getMessage().contains("secret"));
            assertFalse(structured.getCause().getMessage().contains("secret"));
        }
        assertEquals(TransactionPhase.ROLLBACK,
                ((DataTransactionException) transaction.getSuppressed()[0]).phase());
        assertEquals(TransactionPhase.CLEANUP,
                ((DataTransactionException) transaction.getSuppressed()[1]).phase());
        verify(connection).rollback();
        verify(connection, times(2)).setAutoCommit(false);
        verify(connection).close();
    }

    @Test
    void commitFailureReportsUnknownWriteOutcome() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        doThrow(new SQLException("commit lost", "08006")).when(connection).commit();

        CompletionException completion = assertThrows(CompletionException.class,
                () -> new MySQLDataAccess(dataSource, new DirectExecutorService())
                        .executeTransactionally(conn -> "done").join());
        DataTransactionException transaction = assertInstanceOf(
                DataTransactionException.class, completion.getCause());
        assertEquals(TransactionPhase.COMMIT, transaction.phase());
        assertEquals(ExecutionOutcome.MAY_HAVE_APPLIED, transaction.executionOutcome());
        assertFalse(transaction.retryable());
        verify(connection).rollback();
        verify(connection).setAutoCommit(true);
        verify(connection).close();
    }

    @Test
    void postCommitRestoreFailureUsesCleanupPhaseAndAppliedOutcome() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        doNothing().doThrow(new SQLException("restore secret"))
                .when(connection).setAutoCommit(anyBoolean());

        CompletionException completion = assertThrows(CompletionException.class,
                () -> new MySQLDataAccess(dataSource, new DirectExecutorService())
                        .executeTransactionally(conn -> "done").join());
        DataTransactionException transaction = assertInstanceOf(
                DataTransactionException.class, completion.getCause());
        assertEquals(TransactionPhase.CLEANUP, transaction.phase());
        assertEquals(ExecutionOutcome.MAY_HAVE_APPLIED, transaction.executionOutcome());
        assertFalse(transaction.retryable());
        assertFalse(transaction.getCause().getMessage().contains("secret"));
        verify(connection).commit();
        verify(connection).close();
    }

    @Test
    void executeInsertReturnsGeneratedKeyAndStructuresFailure() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet generatedKeys = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString(), org.mockito.Mockito.eq(Statement.RETURN_GENERATED_KEYS)))
                .thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        when(statement.getGeneratedKeys()).thenReturn(generatedKeys);
        when(generatedKeys.next()).thenReturn(true);
        when(generatedKeys.getObject(1)).thenReturn(42L);

        MySQLDataAccess access = new MySQLDataAccess(dataSource, new DirectExecutorService());
        assertEquals(42L, access.executeInsert("INSERT INTO players(name) VALUES (?)", "test").join());

        when(statement.executeUpdate()).thenReturn(0);
        CompletionException completion = assertThrows(CompletionException.class,
                () -> access.executeInsert("INSERT INTO players(name) VALUES (?)", "test").join());
        assertInstanceOf(DataProviderOperationException.class, completion.getCause());
    }

    @Test
    void connectionSqlExceptionsCompleteWithUnavailableFailure() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("password=secret", "08001"));

        CompletionException completion = assertThrows(CompletionException.class,
                () -> new MySQLDataAccess(dataSource, new DirectExecutorService())
                        .executeUpdate("UPDATE test SET value=1").join());
        BackendUnavailableException failure = assertInstanceOf(
                BackendUnavailableException.class, completion.getCause());
        assertEquals("08001", failure.diagnostics().get("sqlState"));
        assertFalse(failure.getMessage().contains("secret"));
    }
}
