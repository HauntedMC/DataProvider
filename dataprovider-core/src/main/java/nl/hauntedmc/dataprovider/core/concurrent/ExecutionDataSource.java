package nl.hauntedmc.dataprovider.core.concurrent;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;

/**
 * A DataSource view which admits JDBC/ORM connection acquisition through the
 * same bounded execution scope as DataAccess.  The underlying pool remains the
 * sole owner of physical connections.
 */
public final class ExecutionDataSource implements ScopedDataSource {

    private final DataSource delegate;
    private final ExecutionHandle execution;

    public ExecutionDataSource(DataSource delegate, ExecutionHandle execution) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate DataSource cannot be null.");
        this.execution = Objects.requireNonNull(execution, "Execution handle cannot be null.");
    }

    @Override
    public Connection getConnection() throws SQLException {
        return acquire(delegate::getConnection);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return acquire(() -> delegate.getConnection(username, password));
    }

    private Connection acquire(CheckedConnectionSupplier supplier) throws SQLException {
        if (execution instanceof ResourceExecutionHandle resourceExecution) {
            return resourceExecution.acquireConnection(supplier);
        }
        try {
            return AsyncTaskSupport.supplyAsync(execution, "jdbc.acquire_connection", supplier::get).join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("JDBC connection acquisition was rejected or failed.", cause);
        }
    }

    @Override public PrintWriter getLogWriter() throws SQLException { return delegate.getLogWriter(); }
    @Override public void setLogWriter(PrintWriter out) throws SQLException {
        throw new SQLException("A scoped DataSource cannot change shared pool logging.");
    }
    @Override public void setLoginTimeout(int seconds) throws SQLException {
        throw new SQLException("A scoped DataSource cannot change shared pool login timeout.");
    }
    @Override public int getLoginTimeout() throws SQLException { return delegate.getLoginTimeout(); }
    @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        // Returning the physical pool would let callers evade the scoped scheduler.
        throw new SQLException("The physical DataSource is not exposed by a scoped provider.");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }

    @FunctionalInterface
    interface CheckedConnectionSupplier {
        Connection get() throws Exception;
    }

    interface ConnectionPermit {
        void release();
    }

    static Connection guardedConnection(Connection delegate, ConnectionPermit permit, Runnable onClose) {
        Objects.requireNonNull(delegate, "Delegate connection cannot be null.");
        Objects.requireNonNull(permit, "Connection permit cannot be null.");
        Objects.requireNonNull(onClose, "Connection close callback cannot be null.");
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("close") && method.getParameterCount() == 0) {
                        try {
                            return method.invoke(delegate);
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        } finally {
                            permit.release();
                            onClose.run();
                        }
                    }
                    if (method.getName().equals("unwrap") && method.getParameterCount() == 1) {
                        Class<?> type = (Class<?>) args[0];
                        if (type.isInstance(proxy)) {
                            return proxy;
                        }
                        throw new SQLException("The physical JDBC connection is not exposed by a scoped provider.");
                    }
                    if (method.getName().equals("isWrapperFor") && method.getParameterCount() == 1) {
                        return ((Class<?>) args[0]).isInstance(proxy);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
        );
    }
}
