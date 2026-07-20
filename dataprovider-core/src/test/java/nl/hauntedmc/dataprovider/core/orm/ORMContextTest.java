package nl.hauntedmc.dataprovider.core.orm;

import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private static ORMContext contextUsing(Session session) {
        SessionFactory sessionFactory = mock(SessionFactory.class);
        LoggerAdapter logger = mock(LoggerAdapter.class);
        when(sessionFactory.openSession()).thenReturn(session);
        return new ORMContext("test-plugin", logger, sessionFactory);
    }
}
