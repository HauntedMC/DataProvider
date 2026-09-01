package nl.hauntedmc.dataprovider.core.orm;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.jdbc.ReturningWork;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.Test;

import java.lang.reflect.UndeclaredThrowableException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionalSessionTest {

    @Test
    void createRejectsNullDelegate() {
        assertThrows(NullPointerException.class, () -> TransactionalSession.create(null));
    }

    @Test
    void sessionViewDelegatesOrdinaryOperationsButBlocksLifecycleMethods() {
        Session delegate = mock(Session.class);
        when(delegate.isOpen()).thenReturn(true);
        Session view = TransactionalSession.create(delegate).view();

        assertTrue(view.isOpen());
        assertThrows(IllegalStateException.class, view::close);
        assertThrows(IllegalStateException.class, view::beginTransaction);
        assertThrows(IllegalStateException.class, view::joinTransaction);

        verify(delegate).isOpen();
        verify(delegate, never()).close();
        verify(delegate, never()).beginTransaction();
        verify(delegate, never()).joinTransaction();
    }

    @Test
    void sessionProxyUsesIdentitySemanticsAndOnlyUnwrapsItself() {
        Session delegate = mock(Session.class);
        Session view = TransactionalSession.create(delegate).view();

        assertTrue(view.equals(view));
        assertFalse(view.equals(delegate));
        assertEquals(System.identityHashCode(view), view.hashCode());
        assertEquals("TransactionalSession[active=true]", view.toString());
        assertSame(view, view.unwrap(Session.class));
        assertThrows(IllegalStateException.class, () -> view.unwrap(Transaction.class));
        assertThrows(IllegalStateException.class, () -> view.unwrap(null));
    }

    @Test
    void transactionViewDelegatesInspectionButBlocksEveryLifecycleOperation() {
        Session delegate = mock(Session.class);
        Transaction transaction = mock(Transaction.class);
        when(delegate.getTransaction()).thenReturn(transaction);
        when(transaction.isActive()).thenReturn(true);
        Transaction view = TransactionalSession.create(delegate).view().getTransaction();

        assertTrue(view.isActive());
        assertThrows(IllegalStateException.class, view::begin);
        assertThrows(IllegalStateException.class, view::commit);
        assertThrows(IllegalStateException.class, view::rollback);
        assertThrows(IllegalStateException.class, view::setRollbackOnly);
        assertThrows(IllegalStateException.class, () -> view.setTimeout(10));

        verify(transaction).isActive();
        verify(transaction, never()).begin();
        verify(transaction, never()).commit();
        verify(transaction, never()).rollback();
        verify(transaction, never()).setRollbackOnly();
        verify(transaction, never()).setTimeout(10);
    }

    @Test
    void nullPhysicalTransactionRemainsNull() {
        Session delegate = mock(Session.class);
        when(delegate.getTransaction()).thenReturn(null);

        assertEquals(null, TransactionalSession.create(delegate).view().getTransaction());
    }

    @Test
    void expireInvalidatesSessionAndPreviouslyReturnedTransaction() {
        Session delegate = mock(Session.class);
        Transaction physicalTransaction = mock(Transaction.class);
        when(delegate.getTransaction()).thenReturn(physicalTransaction);
        TransactionalSession scoped = TransactionalSession.create(delegate);
        Session session = scoped.view();
        Transaction transaction = session.getTransaction();

        scoped.expire();

        assertFalse(session.isOpen());
        assertFalse(transaction.isActive());
        assertEquals("TransactionalSession[active=false]", session.toString());
        assertEquals("TransactionalHibernateTransaction[active=false]", transaction.toString());
        assertThrows(IllegalStateException.class, session::getTransaction);
        assertThrows(IllegalStateException.class, transaction::getStatus);
    }

    @Test
    void doReturningWorkReceivesAnExpiringConnectionAndRestoresItsState() throws Exception {
        Session delegate = mock(Session.class);
        Connection physicalConnection = connectionWithState();
        AtomicReference<Connection> retained = new AtomicReference<>();
        when(delegate.doReturningWork(org.mockito.ArgumentMatchers.<ReturningWork<Object>>any())).thenAnswer(invocation -> {
            ReturningWork<Object> work = invocation.getArgument(0);
            return work.execute(physicalConnection);
        });
        Session session = TransactionalSession.create(delegate).view();

        Object result = session.doReturningWork(connection -> {
            retained.set(connection);
            assertThrows(SQLException.class, connection::commit);
            connection.setReadOnly(false);
            return "result";
        });

        assertEquals("result", result);
        assertTrue(retained.get().isClosed());
        assertThrows(SQLException.class, retained.get()::getCatalog);
        verify(physicalConnection).setReadOnly(false);
        verify(physicalConnection).setReadOnly(true);
        verify(physicalConnection).setAutoCommit(false);
    }

    @Test
    void doWorkPreservesThePrimaryFailureAndSuppressesStateRestoreFailure() throws Exception {
        Session delegate = mock(Session.class);
        Connection physicalConnection = connectionWithState();
        SQLException restoreFailure = new SQLException("restore failed");
        doThrow(restoreFailure).when(physicalConnection).setSchema("schema");
        doAnswer(invocation -> {
            Work work = invocation.getArgument(0);
            work.execute(physicalConnection);
            return null;
        }).when(delegate).doWork(any(Work.class));
        SQLException primary = new SQLException("work failed");
        Session session = TransactionalSession.create(delegate).view();

        UndeclaredThrowableException wrapper = assertThrows(
                UndeclaredThrowableException.class,
                () -> session.doWork(connection -> {
                    throw primary;
                })
        );
        Throwable thrown = wrapper.getUndeclaredThrowable();

        assertSame(primary, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(restoreFailure, thrown.getSuppressed()[0]);
        verify(physicalConnection).setAutoCommit(false);
    }

    @Test
    void delegateFailuresArePropagatedWithoutReflectionWrappers() {
        Session delegate = mock(Session.class);
        IllegalStateException failure = new IllegalStateException("delegate failed");
        when(delegate.isOpen()).thenThrow(failure);
        Session view = TransactionalSession.create(delegate).view();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, view::isOpen);

        assertSame(failure, thrown);
    }

    private static Connection connectionWithState() throws Exception {
        Connection connection = mock(Connection.class);
        Properties clientInfo = new Properties();
        clientInfo.setProperty("application", "dataprovider");
        when(connection.getAutoCommit()).thenReturn(false);
        when(connection.isReadOnly()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.getCatalog()).thenReturn("catalog");
        when(connection.getTypeMap()).thenReturn(Map.of());
        when(connection.getHoldability()).thenReturn(ResultSet.CLOSE_CURSORS_AT_COMMIT);
        when(connection.getSchema()).thenReturn("schema");
        when(connection.getNetworkTimeout()).thenReturn(1_000);
        when(connection.getClientInfo()).thenReturn(clientInfo);
        return connection;
    }
}
