package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.core.concurrent.ScopedDataSource;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DataAccess;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import nl.hauntedmc.dataprovider.database.relational.schema.SchemaManager;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Guards API handles against accidental transfer between cooperative plugins.
 * This is deliberately not represented as a JVM security boundary.
 */
final class IdentityBoundDatabaseProvider {

    private IdentityBoundDatabaseProvider() {
    }

    static DatabaseProvider wrap(DataProviderHandler handler, String pluginId, DatabaseProvider provider) {
        if (provider == null) {
            return null;
        }
        return (DatabaseProvider) proxy(provider, handler, pluginId, providerInterfaces(provider));
    }

    private static Class<?>[] providerInterfaces(DatabaseProvider provider) {
        if (provider instanceof RelationalDatabaseProvider) {
            return new Class<?>[] {RelationalDatabaseProvider.class};
        }
        if (provider instanceof DocumentDatabaseProvider) {
            return new Class<?>[] {DocumentDatabaseProvider.class};
        }
        if (provider instanceof KeyValueDatabaseProvider) {
            return new Class<?>[] {KeyValueDatabaseProvider.class};
        }
        if (provider instanceof MessagingDatabaseProvider) {
            return new Class<?>[] {MessagingDatabaseProvider.class};
        }
        return new Class<?>[] {DatabaseProvider.class};
    }

    private static Object proxy(Object delegate, DataProviderHandler handler, String pluginId, Class<?>[] interfaces) {
        Objects.requireNonNull(delegate, "Delegate cannot be null.");
        Objects.requireNonNull(handler, "Handler cannot be null.");
        Objects.requireNonNull(pluginId, "Plugin id cannot be null.");
        return Proxy.newProxyInstance(
                IdentityBoundDatabaseProvider.class.getClassLoader(), interfaces,
                new GuardedInvocation(delegate, handler, pluginId)
        );
    }

    private static final class GuardedInvocation implements InvocationHandler {
        private final Object delegate;
        private final DataProviderHandler handler;
        private final String pluginId;

        private GuardedInvocation(Object delegate, DataProviderHandler handler, String pluginId) {
            this.delegate = delegate;
            this.handler = handler;
            this.pluginId = pluginId;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(delegate, args);
            }
            handler.requireCallerIdentity(pluginId);
            if (method.getName().equals("unwrap") && args != null && args.length == 1) {
                Class<?> type = (Class<?>) args[0];
                if (type.isInstance(proxy)) {
                    return type.cast(proxy);
                }
                throw new SQLException("The underlying JDBC object is not exposed by a bound provider.");
            }
            if (method.getName().equals("isWrapperFor") && args != null && args.length == 1) {
                return ((Class<?>) args[0]).isInstance(proxy);
            }
            try {
                Object result = method.invoke(delegate, args);
                return bindResult(method.getReturnType(), result, handler, pluginId);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }

    private static Object bindResult(Class<?> returnType, Object result, DataProviderHandler handler, String pluginId) {
        if (result == null) {
            return null;
        }
        if (result instanceof DataSource dataSource) {
            return new GuardedDataSource(dataSource, handler, pluginId);
        }
        if (result instanceof Subscription subscription) {
            return proxy(subscription, handler, pluginId, new Class<?>[] {Subscription.class});
        }
        if (returnType.isInterface() && (DataAccess.class.isAssignableFrom(returnType)
                || SchemaManager.class.isAssignableFrom(returnType)
                || returnType.getPackageName().startsWith("java.sql"))) {
            return proxy(result, handler, pluginId, new Class<?>[] {returnType});
        }
        return result;
    }

    private static final class GuardedDataSource implements ScopedDataSource {
        private final DataSource delegate;
        private final DataProviderHandler handler;
        private final String pluginId;

        private GuardedDataSource(DataSource delegate, DataProviderHandler handler, String pluginId) {
            this.delegate = delegate;
            this.handler = handler;
            this.pluginId = pluginId;
        }

        private void check() { handler.requireCallerIdentity(pluginId); }
        @Override public Connection getConnection() throws SQLException {
            check();
            return (Connection) bindResult(Connection.class, delegate.getConnection(), handler, pluginId);
        }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            check();
            return (Connection) bindResult(Connection.class, delegate.getConnection(user, password), handler, pluginId);
        }
        @Override public PrintWriter getLogWriter() throws SQLException { check(); return delegate.getLogWriter(); }
        @Override public void setLogWriter(PrintWriter writer) throws SQLException { check(); delegate.setLogWriter(writer); }
        @Override public void setLoginTimeout(int seconds) throws SQLException { check(); delegate.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() throws SQLException { check(); return delegate.getLoginTimeout(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException { check(); return delegate.getParentLogger(); }
        @Override public <T> T unwrap(Class<T> type) throws SQLException {
            check();
            if (type.isInstance(this)) {
                return type.cast(this);
            }
            throw new SQLException("The underlying DataSource is not exposed by a bound provider.");
        }
        @Override public boolean isWrapperFor(Class<?> type) throws SQLException { check(); return type.isInstance(this); }
    }
}
