package nl.hauntedmc.dataprovider.core.concurrent;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fair, bounded admission for one physical backend resource. */
public final class ResourceAdmission {

    private final int capacity;
    private final int subscriptionCapacity;
    private final int queueCapacity;
    private final int perPluginQueue;
    private final Object lock = new Object();
    private final LinkedHashMap<String, ArrayDeque<Task>> pluginQueues = new LinkedHashMap<>();
    private final Map<String, Integer> pluginQueued = new LinkedHashMap<>();
    private int queued;
    private int active;
    private int subscriptions;
    private String lastGrantedPlugin;
    private boolean draining;

    public ResourceAdmission(int capacity, DataProviderExecutionRuntime.AdmissionLimits limits) {
        this(capacity, 0, limits);
    }

    public ResourceAdmission(
            int capacity,
            int subscriptionCapacity,
            DataProviderExecutionRuntime.AdmissionLimits limits
    ) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Resource capacity must be positive.");
        }
        if (subscriptionCapacity < 0) {
            throw new IllegalArgumentException("Resource subscription capacity cannot be negative.");
        }
        this.capacity = capacity;
        this.subscriptionCapacity = subscriptionCapacity;
        queueCapacity = Objects.requireNonNull(limits, "Admission limits cannot be null.").perResourceQueue();
        perPluginQueue = limits.perPluginQueue();
    }

    void execute(ResourceExecutionHandle handle, Runnable command) {
        Objects.requireNonNull(command, "Command cannot be null.");
        enqueue(handle, permit -> command.run(), command);
    }

    Connection acquireConnection(
            ResourceExecutionHandle handle,
            ExecutionDataSource.CheckedConnectionSupplier supplier
    ) throws SQLException {
        CompletableFuture<Connection> result = new CompletableFuture<>();
        try {
            Runnable rejectionTarget = new AsyncTaskSupport.RejectionAwareRunnable() {
                @Override public void run() { }
                @Override public void reject(RejectedExecutionException rejection) {
                    result.completeExceptionally(rejection);
                }
                @Override public boolean failed() { return result.isCompletedExceptionally(); }
            };
            enqueue(handle, permit -> {
                try {
                    Connection connection = supplier.get();
                    Connection tracked = handle.trackConnection(connection, permit);
                    permit.handoff();
                    result.complete(tracked);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            }, rejectionTarget);
            return result.join();
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("JDBC connection acquisition was rejected or failed.", cause);
        }
    }

    void cancel(ResourceExecutionHandle handle) {
        List<Task> cancelled = new ArrayList<>();
        synchronized (lock) {
            pluginQueues.values().forEach(queue -> queue.removeIf(task -> {
                if (task.handle != handle) {
                    return false;
                }
                cancelled.add(task);
                queued--;
                decrement(pluginQueued, task.pluginId);
                return true;
            }));
            pluginQueues.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            normalizeCursor();
        }
        cancelled.forEach(task -> {
            task.handle.releaseDeferredQueueSlot();
            rejectTask(task.command, new ExecutionRejectedException(
                    ExecutionRejectedException.Reason.SCOPE_CLOSED,
                    "Execution scope closed for resource " + task.handle.connectionIdentifier()
            ));
        });
        drain();
    }

    boolean tryAcquireSubscription() {
        synchronized (lock) {
            if (subscriptionCapacity == 0 || subscriptions >= subscriptionCapacity) {
                return false;
            }
            subscriptions++;
            return true;
        }
    }

    void releaseSubscription() {
        synchronized (lock) {
            if (subscriptions > 0) {
                subscriptions--;
            }
        }
    }

    private void enqueue(ResourceExecutionHandle handle, AdmissionAction action, Runnable command) {
        if (handle.isClosed()) {
            throw new ExecutionRejectedException(ExecutionRejectedException.Reason.SCOPE_CLOSED,
                    "Execution scope is closed for resource " + handle.connectionIdentifier());
        }
        handle.reserveDeferredQueueSlot();
        boolean accepted = false;
        try {
            synchronized (lock) {
                if (queued >= queueCapacity) {
                    throw new ExecutionRejectedException(ExecutionRejectedException.Reason.CONNECTION_QUEUE_LIMIT,
                            "Resource queue limit reached for " + handle.connectionIdentifier());
                }
                String pluginId = handle.pluginId();
                int pluginCount = pluginQueued.getOrDefault(pluginId, 0);
                if (pluginCount >= perPluginQueue) {
                    throw new ExecutionRejectedException(ExecutionRejectedException.Reason.PLUGIN_QUEUE_LIMIT,
                            "Plugin queue limit reached for " + pluginId);
                }
                pluginQueues.computeIfAbsent(pluginId, ignored -> new ArrayDeque<>())
                        .addLast(new Task(handle, pluginId, action, command));
                pluginQueued.put(pluginId, pluginCount + 1);
                queued++;
                accepted = true;
            }
        } finally {
            if (!accepted) {
                handle.releaseDeferredQueueSlot();
            }
        }
        drain();
    }

    private void drain() {
        synchronized (lock) {
            if (draining) {
                return;
            }
            draining = true;
        }
        while (true) {
            Task task;
            synchronized (lock) {
                task = selectNext();
                if (task == null) {
                    draining = false;
                    return;
                }
                active++;
            }
            task.handle.releaseDeferredQueueSlot();
            try {
                task.handle.delegate().execute(() -> run(task));
            } catch (RejectedExecutionException rejection) {
                release();
                rejectTask(task.command, rejection);
            }
        }
    }

    private Task selectNext() {
        if (active >= capacity || queued == 0 || pluginQueues.isEmpty()) {
            return null;
        }
        List<String> plugins = new ArrayList<>(pluginQueues.keySet());
        int previous = lastGrantedPlugin == null ? -1 : plugins.indexOf(lastGrantedPlugin);
        int index = Math.floorMod(previous + 1, plugins.size());
        String pluginId = plugins.get(index);
        ArrayDeque<Task> queue = pluginQueues.get(pluginId);
        Task task = queue.removeFirst();
        queued--;
        decrement(pluginQueued, pluginId);
        if (queue.isEmpty()) {
            pluginQueues.remove(pluginId);
        }
        lastGrantedPlugin = pluginId;
        return task;
    }

    private void run(Task task) {
        Permit permit = new Permit();
        try {
            task.action.run(permit);
        } finally {
            if (!permit.handedOff.get()) {
                permit.release();
            }
        }
    }

    private void release() {
        synchronized (lock) {
            active--;
        }
        drain();
    }

    private void normalizeCursor() {
        if (pluginQueues.isEmpty()) {
            lastGrantedPlugin = null;
        }
    }

    private static void decrement(Map<String, Integer> values, String key) {
        int next = values.getOrDefault(key, 0) - 1;
        if (next <= 0) {
            values.remove(key);
        } else {
            values.put(key, next);
        }
    }

    private static void rejectTask(Runnable command, RejectedExecutionException rejection) {
        if (command instanceof AsyncTaskSupport.RejectionAwareRunnable aware) {
            aware.reject(rejection);
        }
    }

    private final class Permit implements ExecutionDataSource.ConnectionPermit {
        private final AtomicBoolean handedOff = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();

        private void handoff() {
            handedOff.set(true);
        }

        @Override
        public void release() {
            if (released.compareAndSet(false, true)) {
                ResourceAdmission.this.release();
            }
        }
    }

    private record Task(ResourceExecutionHandle handle, String pluginId, AdmissionAction action, Runnable command) {
    }

    @FunctionalInterface
    private interface AdmissionAction {
        void run(Permit permit);
    }

}
