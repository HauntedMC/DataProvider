package nl.hauntedmc.dataprovider.core.concurrent;

import nl.hauntedmc.dataprovider.database.DatabaseType;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime-owned bounded worker lanes with round-robin fairness between connection scopes.
 */
public final class DataProviderExecutionRuntime implements AutoCloseable {

    private final Map<ExecutionLane, FairLane> lanes;
    private final Duration scopeShutdownGrace;
    private final Duration runtimeShutdownGrace;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DataProviderExecutionRuntime(ExecutionRuntimeConfig config) {
        Objects.requireNonNull(config, "Execution runtime config cannot be null.");
        this.scopeShutdownGrace = config.scopeShutdownGrace();
        this.runtimeShutdownGrace = config.runtimeShutdownGrace();
        EnumMap<ExecutionLane, FairLane> created = new EnumMap<>(ExecutionLane.class);
        config.lanes().forEach((lane, laneConfig) -> created.put(lane, new FairLane(lane, laneConfig)));
        this.lanes = Map.copyOf(created);
    }

    public ExecutionHandle openScope(String pluginId, DatabaseType type, String connectionIdentifier) {
        if (closed.get()) {
            throw new ExecutionRejectedException(ExecutionRejectedException.Reason.RUNTIME_SHUTTING_DOWN,
                    "DataProvider execution runtime is shutting down.");
        }
        String normalizedPlugin = requireText(pluginId, "pluginId");
        String normalizedConnection = requireText(connectionIdentifier, "connectionIdentifier");
        FairLane lane = lanes.get(ExecutionLane.forDatabaseType(Objects.requireNonNull(type, "Database type cannot be null.")));
        return lane.openScope(normalizedPlugin, normalizedConnection, scopeShutdownGrace);
    }

    public Map<ExecutionLane, ExecutionMetricsSnapshot> metrics() {
        EnumMap<ExecutionLane, ExecutionMetricsSnapshot> snapshot = new EnumMap<>(ExecutionLane.class);
        lanes.forEach((lane, runtimeLane) -> snapshot.put(lane, runtimeLane.metrics.snapshot()));
        return Map.copyOf(snapshot);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        lanes.values().forEach(lane -> lane.shutdown(runtimeShutdownGrace));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be null or blank.");
        }
        return value.trim();
    }

    private static final class FairLane {
        private final ExecutionLane lane;
        private final ExecutionRuntimeConfig.LaneConfig config;
        private final Object lock = new Object();
        private final LinkedHashMap<Scope, ArrayDeque<Task>> queues = new LinkedHashMap<>();
        private final Map<String, Integer> pluginQueued = new HashMap<>();
        private final Map<String, Integer> pluginActive = new HashMap<>();
        private final Map<Scope, Integer> scopeActive = new HashMap<>();
        private final Map<Scope, Set<Thread>> activeThreads = new HashMap<>();
        private final List<Thread> workers = new ArrayList<>();
        private final MutableMetrics metrics = new MutableMetrics();
        private boolean stopping;
        private int queuedTasks;
        private int cursor;

        private FairLane(ExecutionLane lane, ExecutionRuntimeConfig.LaneConfig config) {
            this.lane = lane;
            this.config = config;
            for (int index = 0; index < config.workers(); index++) {
                Thread worker = new Thread(this::workerLoop,
                        "dataprovider-" + lane.name().toLowerCase() + "-" + (index + 1));
                worker.setDaemon(true);
                workers.add(worker);
                worker.start();
            }
        }

        private Scope openScope(String pluginId, String connectionIdentifier, Duration shutdownGrace) {
            synchronized (lock) {
                if (stopping) {
                    throw rejection(ExecutionRejectedException.Reason.RUNTIME_SHUTTING_DOWN,
                            "Execution lane " + lane + " is shutting down.");
                }
                Scope scope = new Scope(this, pluginId, connectionIdentifier, shutdownGrace);
                queues.put(scope, new ArrayDeque<>());
                return scope;
            }
        }

        private void submit(Scope scope, Runnable command) {
            Objects.requireNonNull(command, "Command cannot be null.");
            synchronized (lock) {
                if (stopping) {
                    throw rejection(ExecutionRejectedException.Reason.RUNTIME_SHUTTING_DOWN,
                            "Execution lane " + lane + " is shutting down.");
                }
                if (scope.closed.get()) {
                    throw rejection(ExecutionRejectedException.Reason.SCOPE_CLOSED,
                            "Execution scope is closed for " + scope.key());
                }
                ArrayDeque<Task> queue = queues.get(scope);
                if (queue == null) {
                    throw rejection(ExecutionRejectedException.Reason.SCOPE_CLOSED,
                            "Execution scope is no longer registered for " + scope.key());
                }
                if (queuedTasks >= config.queueCapacity()) {
                    throw rejection(ExecutionRejectedException.Reason.LANE_QUEUE_FULL,
                            "Execution lane queue is full for " + lane);
                }
                int pluginQueue = pluginQueued.getOrDefault(scope.pluginId, 0);
                if (pluginQueue >= config.perPluginQueue()) {
                    throw rejection(ExecutionRejectedException.Reason.PLUGIN_QUEUE_LIMIT,
                            "Plugin queue limit reached for " + scope.pluginId);
                }
                if (queue.size() >= config.perConnectionQueue()) {
                    throw rejection(ExecutionRejectedException.Reason.CONNECTION_QUEUE_LIMIT,
                            "Connection queue limit reached for " + scope.key());
                }
                queue.addLast(new Task(command, System.nanoTime()));
                queuedTasks++;
                pluginQueued.put(scope.pluginId, pluginQueue + 1);
                scope.metrics.queuedTasks++;
                metrics.queuedTasks++;
                lock.notifyAll();
            }
        }

        private void workerLoop() {
            while (true) {
                Selection selection;
                synchronized (lock) {
                    while ((selection = selectNext()) == null && !stopping) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            if (stopping) {
                                return;
                            }
                        }
                    }
                    if (selection == null && stopping) {
                        return;
                    }
                    markStarted(selection.scope, selection.task, Thread.currentThread());
                }
                long startedAt = System.nanoTime();
                boolean failed = false;
                try {
                    selection.task.command.run();
                } catch (Throwable throwable) {
                    failed = true;
                } finally {
                    long duration = System.nanoTime() - startedAt;
                    synchronized (lock) {
                        markFinished(selection.scope, Thread.currentThread(), duration, failed);
                        lock.notifyAll();
                    }
                }
            }
        }

        private Selection selectNext() {
            if (queuedTasks == 0 || queues.isEmpty()) {
                return null;
            }
            List<Map.Entry<Scope, ArrayDeque<Task>>> entries = new ArrayList<>(queues.entrySet());
            for (int offset = 0; offset < entries.size(); offset++) {
                int index = Math.floorMod(cursor + offset, entries.size());
                Map.Entry<Scope, ArrayDeque<Task>> entry = entries.get(index);
                Scope scope = entry.getKey();
                ArrayDeque<Task> queue = entry.getValue();
                if (scope.closed.get() || queue.isEmpty()) {
                    continue;
                }
                if (pluginActive.getOrDefault(scope.pluginId, 0) >= config.perPluginActive()) {
                    continue;
                }
                if (scopeActive.getOrDefault(scope, 0) >= config.perConnectionActive()) {
                    continue;
                }
                cursor = (index + 1) % entries.size();
                Task task = queue.removeFirst();
                queuedTasks--;
                decrement(pluginQueued, scope.pluginId);
                scope.metrics.queuedTasks--;
                metrics.queuedTasks--;
                return new Selection(scope, task);
            }
            return null;
        }

        private void markStarted(Scope scope, Task task, Thread thread) {
            pluginActive.merge(scope.pluginId, 1, Integer::sum);
            scopeActive.merge(scope, 1, Integer::sum);
            activeThreads.computeIfAbsent(scope, ignored -> new java.util.HashSet<>()).add(thread);
            long queueWait = System.nanoTime() - task.enqueuedAt;
            scope.metrics.activeTasks++;
            scope.metrics.totalQueueWaitNanos += queueWait;
            scope.metrics.maxQueueWaitNanos = Math.max(scope.metrics.maxQueueWaitNanos, queueWait);
            metrics.activeTasks++;
            metrics.totalQueueWaitNanos += queueWait;
            metrics.maxQueueWaitNanos = Math.max(metrics.maxQueueWaitNanos, queueWait);
        }

        private void markFinished(Scope scope, Thread thread, long duration, boolean failed) {
            decrement(pluginActive, scope.pluginId);
            decrement(scopeActive, scope);
            Set<Thread> threads = activeThreads.get(scope);
            if (threads != null) {
                threads.remove(thread);
                if (threads.isEmpty()) {
                    activeThreads.remove(scope);
                }
            }
            scope.metrics.activeTasks--;
            scope.metrics.totalExecutionNanos += duration;
            scope.metrics.maxExecutionNanos = Math.max(scope.metrics.maxExecutionNanos, duration);
            metrics.activeTasks--;
            metrics.totalExecutionNanos += duration;
            metrics.maxExecutionNanos = Math.max(metrics.maxExecutionNanos, duration);
            if (failed) {
                scope.metrics.failedTasks++;
                metrics.failedTasks++;
            } else {
                scope.metrics.completedTasks++;
                metrics.completedTasks++;
            }
        }

        private void closeScope(Scope scope, Duration grace) {
            List<Task> rejected;
            synchronized (lock) {
                if (!scope.closed.compareAndSet(false, true)) {
                    return;
                }
                ArrayDeque<Task> queue = queues.remove(scope);
                rejected = queue == null ? List.of() : new ArrayList<>(queue);
                for (int index = 0; index < rejected.size(); index++) {
                    queuedTasks--;
                    decrement(pluginQueued, scope.pluginId);
                    scope.metrics.queuedTasks--;
                    scope.metrics.cancelledTasks++;
                    metrics.queuedTasks--;
                    metrics.cancelledTasks++;
                }
                long deadline = System.nanoTime() + grace.toNanos();
                while (scopeActive.getOrDefault(scope, 0) > 0 && System.nanoTime() < deadline) {
                    long remaining = deadline - System.nanoTime();
                    try {
                        lock.wait(Math.max(1L, Math.min(100L, Duration.ofNanos(remaining).toMillis())));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                Set<Thread> threads = activeThreads.get(scope);
                if (threads != null) {
                    threads.forEach(Thread::interrupt);
                }
                lock.notifyAll();
            }
            RejectedExecutionException rejection = new ExecutionRejectedException(
                    ExecutionRejectedException.Reason.SCOPE_CLOSED,
                    "Execution scope closed for " + scope.key());
            rejected.forEach(task -> rejectTask(task.command, rejection));
        }

        private void shutdown(Duration grace) {
            List<Task> rejected = new ArrayList<>();
            synchronized (lock) {
                if (stopping) {
                    return;
                }
                stopping = true;
                queues.forEach((scope, queue) -> {
                    scope.closed.set(true);
                    rejected.addAll(queue);
                    scope.metrics.cancelledTasks += queue.size();
                    scope.metrics.queuedTasks = 0;
                });
                metrics.cancelledTasks += rejected.size();
                metrics.queuedTasks = 0;
                queuedTasks = 0;
                pluginQueued.clear();
                queues.clear();
                lock.notifyAll();
            }
            RejectedExecutionException rejection = new ExecutionRejectedException(
                    ExecutionRejectedException.Reason.RUNTIME_SHUTTING_DOWN,
                    "DataProvider execution runtime is shutting down.");
            rejected.forEach(task -> rejectTask(task.command, rejection));
            long deadline = System.nanoTime() + grace.toNanos();
            for (Thread worker : workers) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    worker.join(Math.max(1L, Duration.ofNanos(remaining).toMillis()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            workers.stream().filter(Thread::isAlive).forEach(Thread::interrupt);
        }

        private ExecutionRejectedException rejection(ExecutionRejectedException.Reason reason, String message) {
            metrics.rejectedTasks++;
            return new ExecutionRejectedException(reason, message);
        }

        private static void rejectTask(Runnable command, RejectedExecutionException rejection) {
            if (command instanceof AsyncTaskSupport.RejectionAwareRunnable aware) {
                aware.reject(rejection);
            }
        }

        private static <K> void decrement(Map<K, Integer> values, K key) {
            int next = values.getOrDefault(key, 0) - 1;
            if (next <= 0) {
                values.remove(key);
            } else {
                values.put(key, next);
            }
        }
    }

    private static final class Scope implements ExecutionHandle {
        private final FairLane lane;
        private final String pluginId;
        private final String connectionIdentifier;
        private final Duration shutdownGrace;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final MutableMetrics metrics = new MutableMetrics();

        private Scope(FairLane lane, String pluginId, String connectionIdentifier, Duration shutdownGrace) {
            this.lane = lane;
            this.pluginId = pluginId;
            this.connectionIdentifier = connectionIdentifier;
            this.shutdownGrace = shutdownGrace;
        }

        @Override
        public void execute(Runnable command) {
            lane.submit(this, command);
        }

        @Override
        public ExecutionMetricsSnapshot metrics() {
            synchronized (lane.lock) {
                return metrics.snapshot();
            }
        }

        @Override
        public boolean isClosed() {
            return closed.get();
        }

        @Override
        public void close() {
            lane.closeScope(this, shutdownGrace);
        }

        private String key() {
            return pluginId + "/" + connectionIdentifier;
        }
    }

    private static final class MutableMetrics {
        private long activeTasks;
        private long queuedTasks;
        private long completedTasks;
        private long failedTasks;
        private long cancelledTasks;
        private long rejectedTasks;
        private long totalQueueWaitNanos;
        private long maxQueueWaitNanos;
        private long totalExecutionNanos;
        private long maxExecutionNanos;

        private ExecutionMetricsSnapshot snapshot() {
            return new ExecutionMetricsSnapshot(activeTasks, queuedTasks, completedTasks, failedTasks, cancelledTasks,
                    rejectedTasks, totalQueueWaitNanos, maxQueueWaitNanos, totalExecutionNanos, maxExecutionNanos);
        }
    }

    private record Task(Runnable command, long enqueuedAt) {
    }

    private record Selection(Scope scope, Task task) {
    }
}
