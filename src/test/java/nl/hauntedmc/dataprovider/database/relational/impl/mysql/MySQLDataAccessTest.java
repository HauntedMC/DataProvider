package nl.hauntedmc.dataprovider.database.relational.impl.mysql;

import nl.hauntedmc.dataprovider.testutil.DirectExecutorService;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
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
        when(metaData.getColumnName(1)).thenReturn("id");
        when(metaData.getColumnName(2)).thenReturn("name");
        when(resultSet.getObject(1)).thenReturn(7);
        when(resultSet.getObject(2)).thenReturn("Remy");

        MySQLDataAccess access = new MySQLDataAccess(dataSource, new DirectExecutorService());
        Map<String, Object> row = access.queryForSingle("SELECT * FROM players WHERE id=?", 7).join();

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

        MySQLDataAccess access = new MySQLDataAccess(dataSource, new DirectExecutorService());
        assertNull(access.queryForSingle("SELECT 1").join());
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
        when(metaData.getColumnName(1)).thenReturn("id");
        when(metaData.getColumnName(2)).thenReturn("name");
        when(resultSet.getObject(1)).thenReturn(1, 2);
        when(resultSet.getObject(2)).thenReturn("a", "b");

        MySQLDataAccess access = new MySQLDataAccess(dataSource, new DirectExecutorService());
        List<Map<String, Object>> rows = access.queryForList("SELECT * FROM players").join();

        assertEquals(2, rows.size());
        assertEquals(1, rows.get(0).get("id"));
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

        Object value = access.queryForSingleValue("SELECT value FROM test").join();
        assertEquals("value", value);

        when(resultSet.next()).thenReturn(false);
        Object missing = access.queryForSingleValue("SELECT value FROM test WHERE id=999").join();
        assertNull(missing);
    }

    @Test
    void executeBatchUpdateAddsEachBatchEntry() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);

        MySQLDataAccess access = new MySQLDataAccess(dataSource, new DirectExecutorService());
        access.executeBatchUpdate(
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
    void executeTransactionallyCommitsOnSuccessAndRollsBackOnFailure() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);

        MySQLDataAccess access = new MySQLDataAccess(dataSource, new DirectExecutorService());
        String result = access.executeTransactionally(conn -> "done").join();
        assertEquals("done", result);
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection).setAutoCommit(true);

        DataSource failingDataSource = mock(DataSource.class);
        Connection failingConnection = mock(Connection.class);
        when(failingDataSource.getConnection()).thenReturn(failingConnection);
        when(failingConnection.getAutoCommit()).thenReturn(false);
        MySQLDataAccess failingAccess = new MySQLDataAccess(failingDataSource, new DirectExecutorService());

        CompletionException ex = assertThrows(
                CompletionException.class,
                () -> failingAccess.executeTransactionally(conn -> {
                    throw new IllegalStateException("boom");
                }).join()
        );

        assertInstanceOf(RuntimeException.class, ex.getCause());
        verify(failingConnection).rollback();
        verify(failingConnection, times(2)).setAutoCommit(false);
    }

    @Test
    void executeInsertReturnsGeneratedKeyAndFailsWhenNoneReturned() throws Exception {
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
        Object key = access.executeInsert("INSERT INTO players(name) VALUES (?)", "test").join();
        assertEquals(42L, key);

        when(statement.executeUpdate()).thenReturn(0);
        CompletionException ex = assertThrows(
                CompletionException.class,
                () -> access.executeInsert("INSERT INTO players(name) VALUES (?)", "test").join()
        );
        assertTrue(ex.getCause().getMessage().contains("Failed to execute insert"));
    }

    @Test
    void wrapsSqlExceptionsInRuntimeExceptions() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("no connection"));
        MySQLDataAccess access = new MySQLDataAccess(dataSource, new DirectExecutorService());

        CompletionException ex = assertThrows(
                CompletionException.class,
                () -> access.executeUpdate("UPDATE test SET value=1").join()
        );
        assertInstanceOf(RuntimeException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Failed to execute update"));
    }
}
