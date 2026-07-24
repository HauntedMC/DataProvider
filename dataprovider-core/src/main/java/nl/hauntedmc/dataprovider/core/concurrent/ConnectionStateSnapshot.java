package nl.hauntedmc.dataprovider.core.concurrent;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** Captures and restores all mutable JDBC Connection state exposed to transactional callbacks. */
public final class ConnectionStateSnapshot {

    private static final java.util.concurrent.Executor DIRECT_EXECUTOR = Runnable::run;

    private final boolean autoCommit;
    private final boolean readOnly;
    private final int isolation;
    private final String catalog;
    private final Map<String, Class<?>> typeMap;
    private final int holdability;
    private final String schema;
    private final int networkTimeout;
    private final Properties clientInfo;

    private ConnectionStateSnapshot(
            boolean autoCommit,
            boolean readOnly,
            int isolation,
            String catalog,
            Map<String, Class<?>> typeMap,
            int holdability,
            String schema,
            int networkTimeout,
            Properties clientInfo
    ) {
        this.autoCommit = autoCommit;
        this.readOnly = readOnly;
        this.isolation = isolation;
        this.catalog = catalog;
        this.typeMap = typeMap;
        this.holdability = holdability;
        this.schema = schema;
        this.networkTimeout = networkTimeout;
        this.clientInfo = clientInfo;
    }

    /**
     * Captures every mutable state value before untrusted code receives a connection.
     * A driver that cannot expose one of these values is rejected rather than allowed
     * to return a potentially contaminated connection to a shared pool.
     */
    public static ConnectionStateSnapshot capture(Connection connection) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        boolean readOnly = connection.isReadOnly();
        int isolation = connection.getTransactionIsolation();
        String catalog = connection.getCatalog();
        Map<String, Class<?>> typeMap = connection.getTypeMap();
        int holdability = connection.getHoldability();
        String schema = connection.getSchema();
        int networkTimeout = connection.getNetworkTimeout();
        Properties clientInfo = copy(connection.getClientInfo());
        return new ConnectionStateSnapshot(autoCommit, readOnly, isolation, catalog,
                typeMap == null ? null : new HashMap<>(typeMap), holdability,
                schema, networkTimeout, clientInfo);
    }

    /** Restores all state, continuing after individual failures so cleanup is comprehensive. */
    public void restore(Connection connection) throws SQLException {
        SQLException failure = null;
        failure = restore(failure, () -> connection.setReadOnly(readOnly));
        failure = restore(failure, () -> connection.setTransactionIsolation(isolation));
        failure = restore(failure, () -> connection.setCatalog(catalog));
        failure = restore(failure, () -> connection.setTypeMap(typeMap == null ? null : new HashMap<>(typeMap)));
        failure = restore(failure, () -> connection.setHoldability(holdability));
        failure = restore(failure, () -> connection.setSchema(schema));
        failure = restore(failure, () -> connection.setNetworkTimeout(DIRECT_EXECUTOR, networkTimeout));
        failure = restore(failure, () -> restoreClientInfo(connection));
        // Enabling auto-commit can commit work still in progress, so it must be last.
        failure = restore(failure, () -> connection.setAutoCommit(autoCommit));
        if (failure != null) {
            throw failure;
        }
    }

    private static Properties copy(Properties source) {
        if (source == null) {
            return null;
        }
        Properties result = new Properties();
        result.putAll(source);
        return result;
    }

    private static SQLException restore(SQLException previous, SqlAction action) {
        try {
            action.run();
        } catch (SQLException failure) {
            if (previous == null) {
                return failure;
            }
            previous.addSuppressed(failure);
        }
        return previous;
    }

    private void restoreClientInfo(Connection connection) throws SQLException {
        Properties current = connection.getClientInfo();
        if (current != null) {
            for (String key : current.stringPropertyNames()) {
                if (clientInfo == null || !clientInfo.containsKey(key)) {
                    connection.setClientInfo(key, null);
                }
            }
        }
        if (clientInfo != null) {
            for (String key : clientInfo.stringPropertyNames()) {
                connection.setClientInfo(key, clientInfo.getProperty(key));
            }
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
