package nl.hauntedmc.dataprovider.core.concurrent;

import nl.hauntedmc.dataprovider.database.DatabaseType;

import java.util.Objects;

/** Adds immutable backend identity to a runtime execution scope. */
public final class ContextualExecutionHandle implements ExecutionHandle {

    private final ExecutionHandle delegate;
    private final String pluginId;
    private final DatabaseType backendType;
    private final String connectionIdentifier;

    public ContextualExecutionHandle(
            ExecutionHandle delegate,
            String pluginId,
            DatabaseType backendType,
            String connectionIdentifier
    ) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate execution handle cannot be null.");
        this.pluginId = requireText(pluginId, "pluginId");
        this.backendType = Objects.requireNonNull(backendType, "Backend type cannot be null.");
        this.connectionIdentifier = requireText(connectionIdentifier, "connectionIdentifier");
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }

    @Override
    public ExecutionMetricsSnapshot metrics() {
        return delegate.metrics();
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public DatabaseType backendType() {
        return backendType;
    }

    @Override
    public String connectionIdentifier() {
        return connectionIdentifier;
    }

    @Override
    public String pluginId() {
        return pluginId;
    }

    @Override
    public boolean tryAcquireSubscription() {
        return delegate.tryAcquireSubscription();
    }

    @Override
    public void releaseSubscription() {
        delegate.releaseSubscription();
    }

    @Override
    public void recordDroppedMessages(long count) {
        delegate.recordDroppedMessages(count);
    }

    @Override
    public void reserveDeferredQueueSlot() {
        delegate.reserveDeferredQueueSlot();
    }

    @Override
    public void releaseDeferredQueueSlot() {
        delegate.releaseDeferredQueueSlot();
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be null or blank.");
        }
        return value.trim();
    }
}
