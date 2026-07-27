package nl.hauntedmc.dataprovider.core.concurrent;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionalConnectionTest {

    @Test
    void createRejectsNullDelegate() {
        assertThrows(NullPointerException.class, () -> TransactionalConnection.create(null));
    }

    @Test
    void viewDelegatesOrdinaryConnectionOperations() throws Exception {
        Connection delegate = mock(Connection.class);
        when(delegate.getCatalog()).thenReturn("dataprovider");
        when(delegate.isReadOnly()).thenReturn(true);
        Connection view = TransactionalConnection.create(delegate).view();

        assertEquals("dataprovider", view.getCatalog());
        assertTrue(view.isReadOnly());
        verify(delegate).getCatalog();
        verify(delegate).isReadOnly();
    }

    @Test
    void viewNeverExposesTransactionLifecycleControl() throws Exception {
        Connection delegate = mock(Connection.class);
        Connection view = TransactionalConnection.create(delegate).view();
        Savepoint savepoint = mock(Savepoint.class);

        assertThrows(SQLException.class, view::close);
        assertThrows(SQLException.class, view::commit);
        assertThrows(SQLException.class, view::rollback);
        assertThrows(SQLException.class, () -> view.rollback(savepoint));
        assertThrows(SQLException.class, () -> view.setAutoCommit(true));
        assertThrows(SQLException.class, view::beginRequest);
        assertThrows(SQLException.class, view::endRequest);

        verify(delegate, never()).close();
        verify(delegate, never()).commit();
        verify(delegate, never()).rollback();
        verify(delegate, never()).rollback(savepoint);
        verify(delegate, never()).setAutoCommit(true);
        verify(delegate, never()).beginRequest();
        verify(delegate, never()).endRequest();
    }

    @Test
    void proxyUsesIdentityObjectSemanticsWithoutTouchingDelegate() {
        Connection delegate = mock(Connection.class);
        Connection first = TransactionalConnection.create(delegate).view();
        Connection second = TransactionalConnection.create(delegate).view();

        assertTrue(first.equals(first));
        assertFalse(first.equals(second));
        assertNotEquals(first.hashCode(), second.hashCode());
        assertEquals("TransactionalConnection[active=true]", first.toString());
    }

    @Test
    void unwrapOnlyReturnsTheScopedProxy() throws Exception {
        Connection view = TransactionalConnection.create(mock(Connection.class)).view();

        assertTrue(view.isWrapperFor(Connection.class));
        assertSame(view, view.unwrap(Connection.class));
        assertFalse(view.isWrapperFor(DataSource.class));
        assertFalse(view.isWrapperFor(null));
        assertThrows(SQLException.class, () -> view.unwrap(DataSource.class));
        assertThrows(SQLException.class, () -> view.unwrap(null));
    }

    @Test
    void statementsResultsAndMetadataAreWrappedAndPointBackToScopedConnection() throws Exception {
        Connection delegate = mock(Connection.class);
        PreparedStatement physicalStatement = mock(PreparedStatement.class);
        ResultSet physicalResults = mock(ResultSet.class);
        DatabaseMetaData physicalMetadata = mock(DatabaseMetaData.class);
        when(delegate.prepareStatement("SELECT 1")).thenReturn(physicalStatement);
        when(physicalStatement.executeQuery()).thenReturn(physicalResults);
        when(delegate.getMetaData()).thenReturn(physicalMetadata);
        when(physicalResults.next()).thenReturn(true);

        Connection view = TransactionalConnection.create(delegate).view();
        PreparedStatement statement = view.prepareStatement("SELECT 1");
        ResultSet results = statement.executeQuery();
        DatabaseMetaData metadata = view.getMetaData();

        assertNotSame(physicalStatement, statement);
        assertNotSame(physicalResults, results);
        assertNotSame(physicalMetadata, metadata);
        assertSame(view, statement.getConnection());
        assertSame(view, metadata.getConnection());
        assertTrue(results.next());
        verify(physicalResults).next();
    }

    @Test
    void childWrappersUseIdentitySemanticsAndDoNotExposePhysicalObjects() throws Exception {
        Connection delegate = mock(Connection.class);
        Statement physical = mock(Statement.class);
        when(delegate.createStatement()).thenReturn(physical);
        Statement statement = TransactionalConnection.create(delegate).view().createStatement();

        assertTrue(statement.equals(statement));
        assertFalse(statement.equals(physical));
        assertTrue(statement.isWrapperFor(Statement.class));
        assertSame(statement, statement.unwrap(Statement.class));
        assertFalse(statement.isWrapperFor(ResultSet.class));
        assertThrows(SQLException.class, () -> statement.unwrap(ResultSet.class));
        assertEquals("TransactionalJdbcObject[active=true]", statement.toString());
    }

    @Test
    void expireInvalidatesConnectionAndEveryPreviouslyReturnedJdbcChild() throws Exception {
        Connection delegate = mock(Connection.class);
        PreparedStatement physicalStatement = mock(PreparedStatement.class);
        ResultSet physicalResults = mock(ResultSet.class);
        when(delegate.prepareStatement("SELECT 1")).thenReturn(physicalStatement);
        when(physicalStatement.executeQuery()).thenReturn(physicalResults);

        TransactionalConnection scoped = TransactionalConnection.create(delegate);
        Connection view = scoped.view();
        PreparedStatement statement = view.prepareStatement("SELECT 1");
        ResultSet results = statement.executeQuery();
        scoped.expire();

        assertTrue(view.isClosed());
        assertTrue(statement.isClosed());
        assertTrue(results.isClosed());
        assertEquals("TransactionalConnection[active=false]", view.toString());
        assertEquals("TransactionalJdbcObject[active=false]", statement.toString());
        assertThrows(SQLException.class, view::getCatalog);
        assertThrows(SQLException.class, statement::executeQuery);
        assertThrows(SQLException.class, results::next);
        verify(delegate, never()).close();
    }

    @Test
    void delegateSqlExceptionsArePropagatedWithoutProxyWrapping() throws Exception {
        Connection delegate = mock(Connection.class);
        SQLException failure = new SQLException("backend failed");
        when(delegate.getCatalog()).thenThrow(failure);
        Connection view = TransactionalConnection.create(delegate).view();

        SQLException thrown = assertThrows(SQLException.class, view::getCatalog);

        assertSame(failure, thrown);
    }

    @Test
    void expirationCannotRaceThroughAnInFlightDelegateCall() throws Exception {
        Connection delegate = mock(Connection.class);
        CountDownLatch enteredDelegate = new CountDownLatch(1);
        CountDownLatch releaseDelegate = new CountDownLatch(1);
        when(delegate.getCatalog()).thenAnswer(invocation -> {
            enteredDelegate.countDown();
            assertTrue(releaseDelegate.await(2, TimeUnit.SECONDS));
            return "catalog";
        });
        TransactionalConnection scoped = TransactionalConnection.create(delegate);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var invocation = executor.submit(() -> scoped.view().getCatalog());
            assertTrue(enteredDelegate.await(2, TimeUnit.SECONDS));
            var expiration = executor.submit(scoped::expire);

            Thread.sleep(20L);
            assertFalse(expiration.isDone());
            releaseDelegate.countDown();

            assertEquals("catalog", invocation.get(2, TimeUnit.SECONDS));
            expiration.get(2, TimeUnit.SECONDS);
            assertTrue(scoped.view().isClosed());
        }
    }
}
