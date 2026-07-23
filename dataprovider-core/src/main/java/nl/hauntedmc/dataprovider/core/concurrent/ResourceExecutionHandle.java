package nl.hauntedmc.dataprovider.core.concurrent;

import nl.hauntedmc.dataprovider.database.DatabaseType;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Connection scope routed through one physical resource's admission gate. */
public final class ResourceExecutionHandle implements ExecutionHandle {

    private final ExecutionHandle delegate;
    private final ResourceAdmission admission;
    private final Set<Connection> issuedConnections = ConcurrentHashMap.newKeySet();
    private final AtomicInteger issuedSubscriptions = new AtomicInteger();

    public ResourceExecutionHandle(ExecutionHandle delegate, ResourceAdmission admission) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate execution handle cannot be null.");
        this.admission = Objects.requireNonNull(admission, "Resource admission cannot be null.");
    }

    @Override public void execute(Runnable command) { admission.execute(this, command); }
    @Override public ExecutionMetricsSnapshot metrics() { return delegate.metrics(); }
    @Override public boolean isClosed() { return delegate.isClosed(); }
    @Override public DatabaseType backendType() { return delegate.backendType(); }
    @Override public String connectionIdentifier() { return delegate.connectionIdentifier(); }
    @Override public String pluginId() { return delegate.pluginId(); }
    @Override
    public boolean tryAcquireSubscription() {
        if (!admission.tryAcquireSubscription()) {
            return false;
        }
        if (delegate.tryAcquireSubscription()) {
            issuedSubscriptions.incrementAndGet();
            return true;
        }
        admission.releaseSubscription();
        return false;
    }

    @Override
    public void releaseSubscription() {
        if (issuedSubscriptions.getAndUpdate(count -> count > 0 ? count - 1 : 0) > 0) {
            admission.releaseSubscription();
            delegate.releaseSubscription();
        }
    }
    @Override public void recordDroppedMessages(long count) { delegate.recordDroppedMessages(count); }
    @Override public void reserveDeferredQueueSlot() { delegate.reserveDeferredQueueSlot(); }
    @Override public void releaseDeferredQueueSlot() { delegate.releaseDeferredQueueSlot(); }

    Connection acquireConnection(ExecutionDataSource.CheckedConnectionSupplier supplier) throws SQLException {
        return admission.acquireConnection(this, supplier);
    }

    ExecutionHandle delegate() {
        return delegate;
    }

    Connection trackConnection(Connection connection, ExecutionDataSource.ConnectionPermit permit) throws SQLException {
        if (isClosed()) {
            try {
                connection.close();
            } finally {
                permit.release();
            }
            throw new SQLException("Execution scope is closed for resource " + connectionIdentifier());
        }
        AtomicReference<Connection> guarded = new AtomicReference<>();
        Connection result = ExecutionDataSource.guardedConnection(connection, permit,
                () -> issuedConnections.remove(guarded.get()));
        guarded.set(result);
        issuedConnections.add(result);
        if (isClosed() && issuedConnections.remove(result)) {
            try {
                result.close();
            } catch (SQLException ignored) {
                // Scope closure must still release the permit even if the driver close fails.
            }
            throw new SQLException("Execution scope is closed for resource " + connectionIdentifier());
        }
        return result;
    }

    @Override
    public void close() {
        admission.cancel(this);
        issuedConnections.forEach(connection -> {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Closing the scope continues so every issued connection gets a close attempt.
            }
        });
        // Do not release subscription capacity here. A subscription owns a physical
        // connection until its listener has actually stopped and calls releaseSubscription().
        delegate.close();
    }
}
