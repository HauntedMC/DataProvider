package nl.hauntedmc.dataprovider.core.orm;

import nl.hauntedmc.dataprovider.core.concurrent.TransactionalConnection;
import nl.hauntedmc.dataprovider.core.concurrent.ConnectionStateSnapshot;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.jdbc.ReturningWork;
import org.hibernate.jdbc.Work;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Callback-scoped Hibernate Session view that keeps transaction ownership with DataProvider. */
final class TransactionalSession {

    private static final String EXPIRED_MESSAGE = "This transactional session is no longer valid.";
    private static final String LIFECYCLE_MESSAGE = "DataProvider exclusively controls the transaction lifecycle.";

    private final Session delegate;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Object monitor = new Object();
    private final Session view;

    private TransactionalSession(Session delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate session cannot be null.");
        view = (Session) Proxy.newProxyInstance(
                Session.class.getClassLoader(),
                new Class<?>[]{Session.class},
                this::invoke
        );
    }

    static TransactionalSession create(Session delegate) {
        return new TransactionalSession(delegate);
    }

    Session view() {
        return view;
    }

    void expire() {
        synchronized (monitor) {
            active.set(false);
        }
    }

    private Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        synchronized (monitor) {
            String name = method.getName();
            if (name.equals("toString") && method.getParameterCount() == 0) {
                return "TransactionalSession[active=" + active.get() + "]";
            }
            if (name.equals("hashCode") && method.getParameterCount() == 0) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals") && method.getParameterCount() == 1) {
                return proxy == arguments[0];
            }
            if (name.equals("isOpen") && method.getParameterCount() == 0 && !active.get()) {
                return false;
            }
            if (!active.get()) {
                throw new IllegalStateException(EXPIRED_MESSAGE);
            }
            if (isLifecycleMethod(method)) {
                throw new IllegalStateException(LIFECYCLE_MESSAGE);
            }
            if (name.equals("unwrap") && method.getParameterCount() == 1) {
                Class<?> type = (Class<?>) arguments[0];
                if (type != null && type.isInstance(proxy)) {
                    return proxy;
                }
                throw new IllegalStateException("The physical Hibernate Session is not exposed by a transaction callback.");
            }
            if (name.equals("getTransaction") && method.getParameterCount() == 0) {
                return transactionView((Transaction) invokeDelegate(method, arguments));
            }
            if (name.equals("doWork") && method.getParameterCount() == 1) {
                Work work = (Work) arguments[0];
                return invokeDelegate(method, new Object[]{(Work) connection -> executeWork(work, connection)});
            }
            if (name.equals("doReturningWork") && method.getParameterCount() == 1) {
                @SuppressWarnings("unchecked")
                ReturningWork<Object> work = (ReturningWork<Object>) arguments[0];
                return invokeDelegate(method,
                        new Object[]{(ReturningWork<Object>) connection -> executeReturningWork(work, connection)});
            }
            Object result = invokeDelegate(method, arguments);
            return result == delegate ? proxy : result;
        }
    }

    private Object transactionView(Transaction transaction) {
        if (transaction == null) {
            return null;
        }
        return Proxy.newProxyInstance(
                Transaction.class.getClassLoader(),
                new Class<?>[]{Transaction.class},
                (proxy, method, arguments) -> invokeTransaction(proxy, transaction, method, arguments)
        );
    }

    private static void executeWork(Work work, java.sql.Connection connection) throws java.sql.SQLException {
        ConnectionStateSnapshot originalState = ConnectionStateSnapshot.capture(connection);
        TransactionalConnection transactionalConnection = TransactionalConnection.create(connection);
        Throwable primary = null;
        try {
            work.execute(transactionalConnection.view());
        } catch (java.sql.SQLException | RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            transactionalConnection.expire();
            restoreConnectionState(connection, originalState, primary);
        }
    }

    private static Object executeReturningWork(ReturningWork<Object> work, java.sql.Connection connection)
            throws java.sql.SQLException {
        ConnectionStateSnapshot originalState = ConnectionStateSnapshot.capture(connection);
        TransactionalConnection transactionalConnection = TransactionalConnection.create(connection);
        Throwable primary = null;
        try {
            return work.execute(transactionalConnection.view());
        } catch (java.sql.SQLException | RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            transactionalConnection.expire();
            restoreConnectionState(connection, originalState, primary);
        }
    }

    private static void restoreConnectionState(
            java.sql.Connection connection,
            ConnectionStateSnapshot originalState,
            Throwable primary
    ) throws java.sql.SQLException {
        try {
            originalState.restore(connection);
        } catch (java.sql.SQLException restoreFailure) {
            if (primary != null) {
                primary.addSuppressed(restoreFailure);
                return;
            }
            throw restoreFailure;
        }
    }

    private Object invokeTransaction(
            Object proxy,
            Transaction transaction,
            java.lang.reflect.Method method,
            Object[] arguments
    ) throws Throwable {
        synchronized (monitor) {
            String name = method.getName();
            if (name.equals("toString") && method.getParameterCount() == 0) {
                return "TransactionalHibernateTransaction[active=" + active.get() + "]";
            }
            if (name.equals("hashCode") && method.getParameterCount() == 0) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals") && method.getParameterCount() == 1) {
                return proxy == arguments[0];
            }
            if (name.equals("isActive") && method.getParameterCount() == 0 && !active.get()) {
                return false;
            }
            if (!active.get()) {
                throw new IllegalStateException(EXPIRED_MESSAGE);
            }
            if (isTransactionLifecycleMethod(method)) {
                throw new IllegalStateException(LIFECYCLE_MESSAGE);
            }
            try {
                return method.invoke(transaction, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }

    private Object invokeDelegate(java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static boolean isLifecycleMethod(java.lang.reflect.Method method) {
        String name = method.getName();
        return (name.equals("close") && method.getParameterCount() == 0)
                || (name.equals("beginTransaction") && method.getParameterCount() == 0)
                || (name.equals("joinTransaction") && method.getParameterCount() == 0)
                || name.equals("inTransaction")
                || name.equals("fromTransaction");
    }

    private static boolean isTransactionLifecycleMethod(java.lang.reflect.Method method) {
        String name = method.getName();
        return name.equals("begin") || name.equals("commit") || name.equals("rollback")
                || name.equals("setRollbackOnly") || name.equals("markRollbackOnly")
                || name.equals("registerSynchronization") || name.equals("runBeforeCompletion")
                || name.equals("runAfterCompletion") || name.equals("setTimeout");
    }
}
