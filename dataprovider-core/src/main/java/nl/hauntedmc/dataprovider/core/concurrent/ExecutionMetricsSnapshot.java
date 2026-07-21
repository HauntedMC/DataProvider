package nl.hauntedmc.dataprovider.core.concurrent;

/** Immutable execution metrics for a lane, plugin, or connection scope. */
public record ExecutionMetricsSnapshot(
        long activeTasks,
        long queuedTasks,
        long completedTasks,
        long failedTasks,
        long cancelledTasks,
        long rejectedTasks,
        long activeSubscriptions,
        long droppedMessages,
        long totalQueueWaitNanos,
        long maxQueueWaitNanos,
        long totalExecutionNanos,
        long maxExecutionNanos
) {
}
