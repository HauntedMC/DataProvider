package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.core.concurrent.ScopedDataSource;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.database.DataAccess;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import nl.hauntedmc.dataprovider.database.keyvalue.KeyValueDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableDelivery;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableMessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableSubscription;
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
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Guards API handles against accidental transfer between cooperative plugins.
 * This is deliberately not represented as a JVM security boundary.
 */
final class IdentityBoundDatabaseProvider {

    private IdentityBoundDatabaseProvider() {
    }

    static DatabaseProvider wrap(DataProviderHandler handler, PluginIdentity identity, DatabaseProvider provider) {
        if (provider == null) {
            return null;
        }
        return (DatabaseProvider) proxy(provider, handler, identity, providerInterfaces(provider));
    }

    static boolean isBoundDataSource(DataSource dataSource) {
        return dataSource instanceof GuardedDataSource;
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

    private static Object proxy(Object delegate, DataProviderHandler handler, PluginIdentity identity, Class<?>[] interfaces) {
        Objects.requireNonNull(delegate, "Delegate cannot be null.");
        Objects.requireNonNull(handler, "Handler cannot be null.");
        return Proxy.newProxyInstance(
                IdentityBoundDatabaseProvider.class.getClassLoader(), interfaces,
                new GuardedInvocation(delegate, handler, identity)
        );
    }

    private static final class GuardedInvocation implements InvocationHandler {
        private final Object delegate;
        private final DataProviderHandler handler;
        private final PluginIdentity identity;

        private GuardedInvocation(Object delegate, DataProviderHandler handler, PluginIdentity identity) {
            this.delegate = delegate;
            this.handler = handler;
            this.identity = identity;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(delegate, args);
            }
            check(handler, identity, isCleanupMethod(method));
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
                Object[] invocationArguments = guardedDurableHandlerArguments(method, args, handler, identity);
                Object result = method.invoke(delegate, invocationArguments);
                return bindResult(method.getReturnType(), result, handler, identity);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        private static Object[] guardedDurableHandlerArguments(
                Method method,
                Object[] arguments,
                DataProviderHandler handler,
                PluginIdentity identity
        ) {
            if (!method.getName().equals("consume") || arguments == null || arguments.length != 6
                    || !(arguments[5] instanceof Consumer originalHandler)) {
                return arguments;
            }
            Object[] guarded = arguments.clone();
            guarded[5] = (Consumer<DurableDelivery<?>>) delivery -> originalHandler.accept(
                    (DurableDelivery<?>) bindResult(DurableDelivery.class, delivery, handler, identity));
            return guarded;
        }
    }

    private static Object bindResult(Class<?> returnType, Object result, DataProviderHandler handler, PluginIdentity identity) {
        if (result == null) {
            return null;
        }
        if (result instanceof DataSource dataSource) {
            return new GuardedDataSource(dataSource, handler, identity);
        }
        if (result instanceof Subscription subscription) {
            return proxy(subscription, handler, identity, new Class<?>[] {Subscription.class});
        }
        if (result instanceof DurableSubscription subscription) {
            return proxy(subscription, handler, identity, new Class<?>[] {DurableSubscription.class});
        }
        if (result instanceof DurableMessagingDataAccess access) {
            return proxy(access, handler, identity, new Class<?>[] {DurableMessagingDataAccess.class});
        }
        if (result instanceof DurableDelivery<?> delivery) {
            return proxy(delivery, handler, identity, new Class<?>[] {DurableDelivery.class});
        }
        if (returnType.isInterface() && (DataAccess.class.isAssignableFrom(returnType)
                || SchemaManager.class.isAssignableFrom(returnType)
                || returnType.getPackageName().startsWith("java.sql"))) {
            return proxy(result, handler, identity, new Class<?>[] {returnType});
        }
        return result;
    }

    private static boolean isCleanupMethod(Method method) {
        String name = method.getName();
        Class<?> declaringClass = method.getDeclaringClass();
        if (name.equals("unsubscribe") && Subscription.class.isAssignableFrom(declaringClass)) {
            return true;
        }
        if (name.equals("closeAsync") && DurableSubscription.class.isAssignableFrom(declaringClass)) {
            return true;
        }
        if (name.equals("shutdown") && MessagingDataAccess.class.isAssignableFrom(declaringClass)) {
            return true;
        }
        if (name.equals("acknowledge") && DurableDelivery.class.isAssignableFrom(declaringClass)) {
            return true;
        }
        if (name.equals("close") && AutoCloseable.class.isAssignableFrom(declaringClass)) {
            return true;
        }
        return Connection.class.isAssignableFrom(declaringClass)
                && (name.equals("commit") || name.equals("rollback") || name.equals("abort"));
    }

    private static final class GuardedDataSource implements ScopedDataSource {
        private final DataSource delegate;
        private final DataProviderHandler handler;
        private final PluginIdentity identity;

        private GuardedDataSource(DataSource delegate, DataProviderHandler handler, PluginIdentity identity) {
            this.delegate = delegate;
            this.handler = handler;
            this.identity = identity;
        }

        private void check() {
            IdentityBoundDatabaseProvider.check(handler, identity, false);
        }

        @Override public Connection getConnection() throws SQLException {
            check();
            return (Connection) bindResult(Connection.class, delegate.getConnection(), handler, identity);
        }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            check();
            return (Connection) bindResult(Connection.class, delegate.getConnection(user, password), handler, identity);
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

    private static void check(DataProviderHandler handler, PluginIdentity identity, boolean cleanup) {
        PluginIdentity boundIdentity = Objects.requireNonNull(identity, "Bound provider identity cannot be null.");
        if (cleanup) {
            handler.requireIdentityForCleanup(boundIdentity);
        } else {
            handler.requireIdentity(boundIdentity);
        }
    }
}
