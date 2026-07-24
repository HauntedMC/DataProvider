package nl.hauntedmc.dataprovider.core.orm;

import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.jdbc.Work;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ORMContextTest {

    @Test
    void rollsBackBeforeClosingSessionWhenCallbackFails() {
        Session session = mock(Session.class);
        Transaction transaction = mock(Transaction.class);
        ORMContext context = contextUsing(session);
        IllegalArgumentException failure = new IllegalArgumentException("write failed");

        when(session.beginTransaction()).thenReturn(transaction);
        when(transaction.isActive()).thenReturn(true);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> context.runInTransaction(ignored -> {
                    throw failure;
                })
        );

        assertSame(failure, thrown.getCause());
        InOrder completionOrder = inOrder(transaction, session);
        completionOrder.verify(transaction).rollback();
        completionOrder.verify(session).close();
        verify(transaction, never()).commit();
    }

    @Test
    void preservesTransactionFailureWhenRollbackAlsoFails() {
        Session session = mock(Session.class);
        Transaction transaction = mock(Transaction.class);
        ORMContext context = contextUsing(session);
        IllegalArgumentException transactionFailure = new IllegalArgumentException("write failed");
        IllegalStateException rollbackFailure = new IllegalStateException("rollback failed");

        when(session.beginTransaction()).thenReturn(transaction);
        when(transaction.isActive()).thenReturn(true);
        doThrow(rollbackFailure).when(transaction).rollback();

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> context.runInTransaction(ignored -> {
                    throw transactionFailure;
                })
        );

        assertSame(transactionFailure, thrown.getCause());
        assertEquals(1, transactionFailure.getSuppressed().length);
        assertSame(rollbackFailure, transactionFailure.getSuppressed()[0]);
        InOrder completionOrder = inOrder(transaction, session);
        completionOrder.verify(transaction).rollback();
        completionOrder.verify(session).close();
    }

    @Test
    void callbackCannotControlOrRetainHibernateTransactionResources() throws Exception {
        Session session = mock(Session.class);
        Transaction transaction = mock(Transaction.class);
        Connection connection = mock(Connection.class);
        AtomicReference<Session> retainedSession = new AtomicReference<>();
        AtomicReference<Connection> retainedConnection = new AtomicReference<>();
        ORMContext context = contextUsing(session);
        when(session.beginTransaction()).thenReturn(transaction);
        when(session.getTransaction()).thenReturn(transaction);
        when(connection.getSchema()).thenReturn("initial_schema");
        doAnswer(invocation -> {
            ((Work) invocation.getArgument(0)).execute(connection);
            return null;
        }).when(session).doWork(any(Work.class));

        context.runInTransaction(callbackSession -> {
            retainedSession.set(callbackSession);
            assertThrows(IllegalStateException.class, callbackSession::close);
            assertThrows(IllegalStateException.class, () -> callbackSession.getTransaction().commit());
            assertSame(callbackSession, callbackSession.unwrap(Session.class));
            callbackSession.doWork(callbackConnection -> {
                retainedConnection.set(callbackConnection);
                assertThrows(SQLException.class, callbackConnection::commit);
                callbackConnection.setSchema("callback_schema");
            });
            return null;
        });

        assertTrue(!retainedSession.get().isOpen());
        assertThrows(IllegalStateException.class, retainedSession.get()::flush);
        assertThrows(SQLException.class, retainedConnection.get()::createStatement);
        verify(connection).setSchema("initial_schema");
        verify(transaction).commit();
        verify(session).close();
    }

    private static ORMContext contextUsing(Session session) {
        SessionFactory sessionFactory = mock(SessionFactory.class);
        LoggerAdapter logger = mock(LoggerAdapter.class);
        when(sessionFactory.openSession()).thenReturn(session);
        return new ORMContext("test-plugin", logger, sessionFactory);
    }
}
