package nl.hauntedmc.dataprovider.core.concurrent;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A callback-scoped Connection view.  It deliberately does not expose pool or
 * transaction ownership to code supplied by a plugin.
 */
public final class TransactionalConnection {

    private static final String EXPIRED_MESSAGE = "This transactional connection is no longer valid.";
    private static final String LIFECYCLE_MESSAGE = "DataProvider exclusively controls the transaction lifecycle.";

    private final Connection delegate;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Object monitor = new Object();
    private final Connection view;

    private TransactionalConnection(Connection delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate connection cannot be null.");
        view = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                this::invoke
        );
    }

    public static TransactionalConnection create(Connection delegate) {
        return new TransactionalConnection(delegate);
    }

    public Connection view() {
        return view;
    }

    /** Makes every retained reference unusable before DataProvider commits or rolls back. */
    public void expire() {
        synchronized (monitor) {
            active.set(false);
        }
    }

    private Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        synchronized (monitor) {
            String name = method.getName();
            if (name.equals("toString") && method.getParameterCount() == 0) {
                return "TransactionalConnection[active=" + active.get() + "]";
            }
            if (name.equals("hashCode") && method.getParameterCount() == 0) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals") && method.getParameterCount() == 1) {
                return proxy == arguments[0];
            }
            if (name.equals("isClosed") && method.getParameterCount() == 0) {
                return !active.get();
            }
            if (!active.get()) {
                throw new SQLException(EXPIRED_MESSAGE);
            }
            if (isLifecycleMethod(method)) {
                throw new SQLException(LIFECYCLE_MESSAGE);
            }
            if (name.equals("unwrap") && method.getParameterCount() == 1) {
                Class<?> type = (Class<?>) arguments[0];
                if (type != null && type.isInstance(proxy)) {
                    return proxy;
                }
                throw new SQLException("The physical JDBC connection is not exposed by a transaction callback.");
            }
            if (name.equals("isWrapperFor") && method.getParameterCount() == 1) {
                Class<?> type = (Class<?>) arguments[0];
                return type != null && type.isInstance(proxy);
            }
            try {
                return wrapJdbcChild(method.getReturnType(), method.invoke(delegate, arguments));
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }

    private Object wrapJdbcChild(Class<?> returnType, Object result) {
        if (result == null || !isJdbcChild(returnType)) {
            return result;
        }
        return Proxy.newProxyInstance(
                returnType.getClassLoader(),
                new Class<?>[]{returnType},
                (proxy, method, arguments) -> invokeJdbcChild(proxy, result, method, arguments)
        );
    }

    private Object invokeJdbcChild(
            Object proxy,
            Object child,
            java.lang.reflect.Method method,
            Object[] arguments
    ) throws Throwable {
        synchronized (monitor) {
            String name = method.getName();
            if (name.equals("toString") && method.getParameterCount() == 0) {
                return "TransactionalJdbcObject[active=" + active.get() + "]";
            }
            if (name.equals("hashCode") && method.getParameterCount() == 0) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals") && method.getParameterCount() == 1) {
                return proxy == arguments[0];
            }
            if (name.equals("isClosed") && method.getParameterCount() == 0 && !active.get()) {
                return true;
            }
            if (!active.get()) {
                throw new SQLException(EXPIRED_MESSAGE);
            }
            if (name.equals("getConnection") && method.getParameterCount() == 0) {
                return view;
            }
            if (name.equals("unwrap") && method.getParameterCount() == 1) {
                Class<?> type = (Class<?>) arguments[0];
                if (type != null && type.isInstance(proxy)) {
                    return proxy;
                }
                throw new SQLException("The physical JDBC object is not exposed by a transaction callback.");
            }
            if (name.equals("isWrapperFor") && method.getParameterCount() == 1) {
                Class<?> type = (Class<?>) arguments[0];
                return type != null && type.isInstance(proxy);
            }
            try {
                return wrapJdbcChild(method.getReturnType(), method.invoke(child, arguments));
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }

    private static boolean isJdbcChild(Class<?> type) {
        return type == Statement.class || type == java.sql.PreparedStatement.class
                || type == java.sql.CallableStatement.class || type == ResultSet.class || type == DatabaseMetaData.class;
    }

    private static boolean isLifecycleMethod(java.lang.reflect.Method method) {
        String name = method.getName();
        return (name.equals("close") && method.getParameterCount() == 0)
                || (name.equals("abort") && method.getParameterCount() == 1)
                || (name.equals("commit") && method.getParameterCount() == 0)
                || name.equals("rollback")
                || (name.equals("setAutoCommit") && method.getParameterCount() == 1)
                // These methods have no JDBC getter, so they cannot be restored reliably.
                || name.equals("beginRequest")
                || name.equals("endRequest")
                || name.equals("setShardingKey")
                || name.equals("setShardingKeyIfValid");
    }
}
