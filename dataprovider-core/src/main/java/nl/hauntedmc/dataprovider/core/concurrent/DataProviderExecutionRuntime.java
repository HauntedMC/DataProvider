package nl.hauntedmc.dataprovider.core.concurrent;

import nl.hauntedmc.dataprovider.database.DatabaseType;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime-owned bounded worker lanes with work-conserving plugin-first fairness. */
public final class DataProviderExecutionRuntime implements AutoCloseable {

    private final Map<ExecutionLane, FairLane> lanes;
    private final Duration scopeShutdownGrace;
    private final Duration runtimeShutdownGrace;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DataProviderExecutionRuntime(ExecutionRuntimeConfig config) {
        Objects.requireNonNull(config, "Execution runtime config cannot be null.");
        scopeShutdownGrace = config.scopeShutdownGrace();
        runtimeShutdownGrace = config.runtimeShutdownGrace();
        EnumMap<ExecutionLane, FairLane> created = new EnumMap<>(ExecutionLane.class);
        config.lanes().forEach((lane, laneConfig) -> created.put(lane, new FairLane(
                lane,
                laneConfig,
                config.messagingGlobalSubscriptions(),
                config.messagingPerPluginSubscriptions(),
                config.messagingPerConnectionSubscriptions()
        )));
        lanes = Map.copyOf(created);
    }

    public ExecutionHandle openScope(String pluginId, DatabaseType type, String connectionIdentifier) {
        if (closed.get()) {
            throw new ExecutionRejectedException(
                    ExecutionRejectedException.Reason.RUNTIME_SHUTTING_DOWN,
                    "DataProvider execution runtime is shutting down."
            );
        }
        FairLane lane = lanes.get(ExecutionLane.forDatabaseType(
                Objects.requireNonNull(type, "Database type cannot be null.")));
        return lane.openScope(
                requireText(pluginId, "pluginId"),
                requireText(connectionIdentifier, "connectionIdentifier"),
                scopeShutdownGrace
        );
    }

    public Map<ExecutionLane, ExecutionMetricsSnapshot> metrics() {
        EnumMap<ExecutionLane, ExecutionMetricsSnapshot> snapshot = new EnumMap<>(ExecutionLane.class);
        lanes.forEach((lane, runtimeLane) -> snapshot.put(lane, runtimeLane.snapshot()));
        return Map.copyOf(snapshot);
    }

    public Map<String, ExecutionMetricsSnapshot> pluginMetrics(ExecutionLane lane) {
        return lanes.get(Objects.requireNonNull(lane, "Lane cannot be null.")).pluginSnapshots();
    }

    public Map<String, ExecutionMetricsSnapshot> connectionMetrics(ExecutionLane lane) {
        return lanes.get(Objects.requireNonNull(lane, "Lane cannot be null.")).connectionSnapshots();
    }

    public AdmissionLimits admissionLimits(DatabaseType type) {
        ExecutionRuntimeConfig.LaneConfig config = lanes.get(ExecutionLane.forDatabaseType(
                Objects.requireNonNull(type, "Database type cannot be null."))).config;
        return new AdmissionLimits(config.perPluginQueue(), config.perResourceQueue());
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
        private final int globalSubscriptionLimit;
        private final int pluginSubscriptionLimit;
        private final int connectionSubscriptionLimit;
        private final Object lock = new Object();
        private final LinkedHashMap<Scope, ArrayDeque<Task>> queues = new LinkedHashMap<>();
        private final LinkedHashMap<String, List<Scope>> pluginScopes = new LinkedHashMap<>();
        private final Map<String, Integer> pluginQueued = new HashMap<>();
        private final Map<String, Integer> resourceQueued = new HashMap<>();
        private final Map<String, Integer> pluginSubscriptions = new HashMap<>();
        private final Map<String, Integer> pluginScopeCursor = new HashMap<>();
        private final Map<Scope, Integer> scopeActive = new HashMap<>();
        private final Map<Scope, Integer> scopeSubscriptions = new HashMap<>();
        private final Map<Scope, Set<Thread>> activeThreads = new HashMap<>();
        private final Map<Thread, ActiveTask> activeTasks = new HashMap<>();
        private final Map<String, MutableMetrics> pluginMetrics = new HashMap<>();
        private final List<Thread> workers = new ArrayList<>();
        private final MutableMetrics metrics = new MutableMetrics();
        private boolean stopping;
        private int queuedTasks;
        private int subscriptions;
        private int pluginCursor;

        private FairLane(
                ExecutionLane lane,
                ExecutionRuntimeConfig.LaneConfig config,
                int globalSubscriptionLimit,
                int pluginSubscriptionLimit,
                int connectionSubscriptionLimit
        ) {
            this.lane = lane;
            this.config = config;
            this.globalSubscriptionLimit = globalSubscriptionLimit;
            this.pluginSubscriptionLimit = pluginSubscriptionLimit;
            this.connectionSubscriptionLimit = connectionSubscriptionLimit;
            for (int index = 0; index < config.workers(); index++) {
                Thread worker = new Thread(
                        this::workerLoop,
                        "dataprovider-" + lane.name().toLowerCase() + "-" + (index + 1)
                );
                worker.setDaemon(true);
                workers.add(worker);
                worker.start();
            }
        }

        private Scope openScope(String pluginId, String connectionIdentifier, Duration shutdownGrace) {
            synchronized (lock) {
                if (stopping) {
                    throw reject(null, ExecutionRejectedException.Reason.RUNTIME_SHUTTING_DOWN,
                            "Execution lane " + lane + " is shutting down.");
                }
                Scope scope = new Scope(this, pluginId, connectionIdentifier, shutdownGrace);
                queues.put(scope, new ArrayDeque<>());
                pluginScopes.computeIfAbsent(pluginId, ignored -> new ArrayList<>()).add(scope);
                pluginMetrics.computeIfAbsent(pluginId, ignored -> new MutableMetrics());
                return scope;
            }
        }

        private void submit(Scope scope, Runnable command) {
            Objects.requireNonNull(command, "Command cannot be null.");
            synchronized (lock) {
                if (stopping) {
                    throw reject(scope, ExecutionRejectedException.Reason.RUNTIME_SHUTTING_DOWN,
                            "Execution lane " + lane + " is shutting down.");
                }
                ArrayDeque<Task> queue = queues.get(scope);
                if (scope.closed.get() || queue == null) {
                    throw reject(scope, ExecutionRejectedException.Reason.SCOPE_CLOSED,
                            "Execution scope is closed for " + scope.key());
                }
                if (queuedTasks >= config.queueCapacity()) {
                    throw reject(scope, ExecutionRejectedException.Reason.LANE_QUEUE_FULL,
                            "Execution lane queue is full for " + lane);
                }
                int pluginQueue = pluginQueued.getOrDefault(scope.pluginId, 0);
                if (pluginQueue >= config.perPluginQueue()) {
                    throw reject(scope, ExecutionRejectedException.Reason.PLUGIN_QUEUE_LIMIT,
                            "Plugin queue limit reached for " + scope.pluginId);
                }
                String resource = scope.connectionIdentifier;
                if (resourceQueued.getOrDefault(resource, 0) >= config.perResourceQueue()) {
                    throw reject(scope, ExecutionRejectedException.Reason.CONNECTION_QUEUE_LIMIT,
                            "Resource queue limit reached for " + resource);
                }
                queue.addLast(new Task(command, System.nanoTime()));
                queuedTasks++;
                pluginQueued.put(scope.pluginId, pluginQueue + 1);
                resourceQueued.merge(resource, 1, Integer::sum);
                changeQueued(scope, 1);
                lock.notifyAll();
            }
        }

        private void reserveDeferredQueueSlot(Scope scope) {
            synchronized (lock) {
                if (stopping) {
                    throw reject(scope, ExecutionRejectedException.Reason.RUNTIME_SHUTTING_DOWN,
                            "Execution lane " + lane + " is shutting down.");
                }
                if (scope.closed.get() || !queues.containsKey(scope)) {
                    throw reject(scope, ExecutionRejectedException.Reason.SCOPE_CLOSED,
                            "Execution scope is closed for " + scope.key());
                }
                if (queuedTasks >= config.queueCapacity()) {
                    throw reject(scope, ExecutionRejectedException.Reason.LANE_QUEUE_FULL,
                            "Execution lane queue is full for " + lane);
                }
                int pluginQueue = pluginQueued.getOrDefault(scope.pluginId, 0);
                if (pluginQueue >= config.perPluginQueue()) {
                    throw reject(scope, ExecutionRejectedException.Reason.PLUGIN_QUEUE_LIMIT,
                            "Plugin queue limit reached for " + scope.pluginId);
                }
                queuedTasks++;
                pluginQueued.put(scope.pluginId, pluginQueue + 1);
                changeQueued(scope, 1);
            }
        }

        private void releaseDeferredQueueSlot(Scope scope) {
            synchronized (lock) {
                if (queuedTasks <= 0) {
                    return;
                }
                queuedTasks--;
                decrement(pluginQueued, scope.pluginId);
                changeQueued(scope, -1);
                lock.notifyAll();
            }
        }

        private boolean tryAcquireSubscription(Scope scope) {
            synchronized (lock) {
                if (lane != ExecutionLane.MESSAGING) {
                    return true;
                }
                if (stopping || scope.closed.get()) {
                    reject(scope, stopping
                                    ? ExecutionRejectedException.Reason.RUNTIME_SHUTTING_DOWN
                                    : ExecutionRejectedException.Reason.SCOPE_CLOSED,
                            "Messaging execution scope is unavailable for " + scope.key());
                    return false;
                }
                int pluginCount = pluginSubscriptions.getOrDefault(scope.pluginId, 0);
                int connectionCount = scopeSubscriptions.getOrDefault(scope, 0);
                if (subscriptions >= globalSubscriptionLimit
                        || pluginCount >= pluginSubscriptionLimit
                        || connectionCount >= connectionSubscriptionLimit) {
                    reject(scope, ExecutionRejectedException.Reason.SUBSCRIPTION_LIMIT,
                            "Messaging subscription budget exhausted for " + scope.key());
                    return false;
                }
                subscriptions++;
                pluginSubscriptions.put(scope.pluginId, pluginCount + 1);
                scopeSubscriptions.put(scope, connectionCount + 1);
                scope.metrics.activeSubscriptions++;
                pluginMetrics.get(scope.pluginId).activeSubscriptions++;
                metrics.activeSubscriptions++;
                return true;
            }
        }

        private void releaseSubscription(Scope scope) {
            synchronized (lock) {
                int count = scopeSubscriptions.getOrDefault(scope, 0);
                if (count <= 0) {
                    return;
                }
                decrement(scopeSubscriptions, scope);
                decrement(pluginSubscriptions, scope.pluginId);
                subscriptions--;
                scope.metrics.activeSubscriptions--;
                pluginMetrics.get(scope.pluginId).activeSubscriptions--;
                metrics.activeSubscriptions--;
            }
        }

        private void recordDroppedMessages(Scope scope, long count) {
            if (count <= 0) {
                return;
            }
            synchronized (lock) {
                scope.metrics.droppedMessages += count;
                pluginMetrics.get(scope.pluginId).droppedMessages += count;
                metrics.droppedMessages += count;
            }
        }

        private void workerLoop() {
            while (true) {
                Selection selection;
                Thread worker = Thread.currentThread();
                synchronized (lock) {
                    while ((selection = selectNext()) == null && !stopping) {
                        try {
                            lock.wait();
                        } catch (InterruptedException ignored) {
                            Thread.interrupted();
                        }
                    }
                    if (selection == null) {
                        return;
                    }
                    markStarted(selection.scope, selection.task, worker);
                }

                long startedAt = System.nanoTime();
                boolean failed = false;
                try {
                    selection.task.command.run();
                    if (selection.task.command instanceof AsyncTaskSupport.RejectionAwareRunnable aware) {
                        failed = aware.failed();
                    }
                } catch (Throwable throwable) {
                    failed = true;
                } finally {
                    synchronized (lock) {
                        markFinished(selection.scope, worker, System.nanoTime() - startedAt, failed);
                        lock.notifyAll();
                    }
                    // A timed-out scope may interrupt a shared worker. Never leak that flag into another plugin's task.
                    Thread.interrupted();
                }
            }
        }

        private Selection selectNext() {
            if (queuedTasks == 0 || pluginScopes.isEmpty()) {
                return null;
            }
            List<String> plugins = new ArrayList<>(pluginScopes.keySet());
            for (int pluginOffset = 0; pluginOffset < plugins.size(); pluginOffset++) {
                int pluginIndex = Math.floorMod(pluginCursor + pluginOffset, plugins.size());
                String pluginId = plugins.get(pluginIndex);
                List<Scope> scopes = pluginScopes.get(pluginId);
                if (scopes == null || scopes.isEmpty()) {
                    continue;
                }
                int start = Math.floorMod(pluginScopeCursor.getOrDefault(pluginId, 0), scopes.size());
                for (int scopeOffset = 0; scopeOffset < scopes.size(); scopeOffset++) {
                    int scopeIndex = Math.floorMod(start + scopeOffset, scopes.size());
                    Scope scope = scopes.get(scopeIndex);
                    ArrayDeque<Task> queue = queues.get(scope);
                    if (scope.closed.get() || queue == null || queue.isEmpty()) {
                        continue;
                    }
                    pluginCursor = (pluginIndex + 1) % plugins.size();
                    pluginScopeCursor.put(pluginId, (scopeIndex + 1) % scopes.size());
                    Task task = queue.removeFirst();
                    queuedTasks--;
                    decrement(pluginQueued, pluginId);
                    decrement(resourceQueued, scope.connectionIdentifier);
                    changeQueued(scope, -1);
                    return new Selection(scope, task);
                }
            }
            return null;
        }

        private void markStarted(Scope scope, Task task, Thread thread) {
            scopeActive.merge(scope, 1, Integer::sum);
            activeThreads.computeIfAbsent(scope, ignored -> new HashSet<>()).add(thread);
            activeTasks.put(thread, new ActiveTask(scope, task));
            long wait = System.nanoTime() - task.enqueuedAt;
            MutableMetrics plugin = pluginMetrics.get(scope.pluginId);
            scope.metrics.activeTasks++;
            plugin.activeTasks++;
            metrics.activeTasks++;
            scope.metrics.recordQueueWait(wait);
            plugin.recordQueueWait(wait);
            metrics.recordQueueWait(wait);
        }

        private void markFinished(Scope scope, Thread thread, long duration, boolean failed) {
            decrement(scopeActive, scope);
            activeTasks.remove(thread);
            Set<Thread> threads = activeThreads.get(scope);
            if (threads != null) {
                threads.remove(thread);
                if (threads.isEmpty()) {
                    activeThreads.remove(scope);
                }
            }
            MutableMetrics plugin = pluginMetrics.get(scope.pluginId);
            scope.metrics.activeTasks--;
            plugin.activeTasks--;
            metrics.activeTasks--;
            scope.metrics.recordExecution(duration, failed);
            plugin.recordExecution(duration, failed);
            metrics.recordExecution(duration, failed);
        }

        private void closeScope(Scope scope, Duration grace) {
            List<Task> cancelled;
            List<ActiveTask> timedOut;
            synchronized (lock) {
                if (!scope.closed.compareAndSet(false, true)) {
                    return;
                }
                ArrayDeque<Task> queue = queues.remove(scope);
                cancelled = queue == null ? List.of() : new ArrayList<>(queue);
                for (int index = 0; index < cancelled.size(); index++) {
                    queuedTasks--;
                    decrement(pluginQueued, scope.pluginId);
                    decrement(resourceQueued, scope.connectionIdentifier);
                    changeQueued(scope, -1);
                    countCancellation(scope);
                }
                removeScopeFromPlugin(scope);
                releaseAllSubscriptions(scope);
                awaitActive(scope, grace);
                timedOut = activeTasks.values().stream()
                        .filter(active -> active.scope == scope)
                        .toList();
                timedOut.forEach(active -> {
                    if (active.task.markCancelled()) {
                        countCancellation(scope);
                    }
                    rejectTask(active.task.command, scopeClosedRejection(scope));
                });
                Set<Thread> threads = activeThreads.get(scope);
                if (threads != null) {
                    threads.forEach(Thread::interrupt);
                }
                lock.notifyAll();
            }
            RejectedExecutionException rejection = scopeClosedRejection(scope);
            cancelled.forEach(task -> rejectTask(task.command, rejection));
        }

        private void removeScopeFromPlugin(Scope scope) {
            List<Scope> scopes = pluginScopes.get(scope.pluginId);
            if (scopes == null) {
                return;
            }
            scopes.remove(scope);
            if (scopes.isEmpty()) {
                pluginScopes.remove(scope.pluginId);
                pluginScopeCursor.remove(scope.pluginId);
                if (pluginCursor > 0) {
                    pluginCursor--;
                }
            } else {
                pluginScopeCursor.computeIfPresent(scope.pluginId,
                        (ignored, cursor) -> Math.floorMod(cursor, scopes.size()));
            }
        }

        private void awaitActive(Scope scope, Duration grace) {
            long deadline = System.nanoTime() + grace.toNanos();
            while (scopeActive.getOrDefault(scope, 0) > 0 && System.nanoTime() < deadline) {
                long remaining = deadline - System.nanoTime();
                try {
                    lock.wait(Math.max(1L, Math.min(100L, Duration.ofNanos(remaining).toMillis())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void releaseAllSubscriptions(Scope scope) {
            int count = scopeSubscriptions.getOrDefault(scope, 0);
            scopeSubscriptions.remove(scope);
            if (count <= 0) {
                return;
            }
            subscriptions = Math.max(0, subscriptions - count);
            int pluginCount = pluginSubscriptions.getOrDefault(scope.pluginId, 0) - count;
            if (pluginCount <= 0) {
                pluginSubscriptions.remove(scope.pluginId);
            } else {
                pluginSubscriptions.put(scope.pluginId, pluginCount);
            }
            MutableMetrics plugin = pluginMetrics.get(scope.pluginId);
            scope.metrics.activeSubscriptions = Math.max(0, scope.metrics.activeSubscriptions - count);
            plugin.activeSubscriptions = Math.max(0, plugin.activeSubscriptions - count);
            metrics.activeSubscriptions = Math.max(0, metrics.activeSubscriptions - count);
        }

        private void shutdown(Duration grace) {
            List<Task> cancelled = new ArrayList<>();
            synchronized (lock) {
                if (stopping) {
                    return;
                }
                stopping = true;
                queues.forEach((scope, queue) -> {
                    scope.closed.set(true);
                    cancelled.addAll(queue);
                    for (int index = 0; index < queue.size(); index++) {
                        countCancellation(scope);
                    }
                    scope.metrics.queuedTasks = 0;
                    releaseAllSubscriptions(scope);
                });
                metrics.queuedTasks = 0;
                pluginMetrics.values().forEach(plugin -> plugin.queuedTasks = 0);
                queuedTasks = 0;
                pluginQueued.clear();
                resourceQueued.clear();
                queues.clear();
                pluginScopes.clear();
                pluginScopeCursor.clear();
                lock.notifyAll();
            }

            RejectedExecutionException rejection = new ExecutionRejectedException(
                    ExecutionRejectedException.Reason.RUNTIME_SHUTTING_DOWN,
                    "DataProvider execution runtime is shutting down."
            );
            cancelled.forEach(task -> rejectTask(task.command, rejection));

            long deadline = System.nanoTime() + grace.toNanos();
            synchronized (lock) {
                while (!activeTasks.isEmpty() && System.nanoTime() < deadline) {
                    long remaining = deadline - System.nanoTime();
                    try {
                        lock.wait(Math.max(1L, Math.min(100L, Duration.ofNanos(remaining).toMillis())));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                for (Map.Entry<Thread, ActiveTask> entry : new ArrayList<>(activeTasks.entrySet())) {
                    ActiveTask active = entry.getValue();
                    if (active.task.markCancelled()) {
                        countCancellation(active.scope);
                    }
                    rejectTask(active.task.command, rejection);
                    entry.getKey().interrupt();
                }
                lock.notifyAll();
            }

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

        private void countCancellation(Scope scope) {
            scope.metrics.cancelledTasks++;
            pluginMetrics.get(scope.pluginId).cancelledTasks++;
            metrics.cancelledTasks++;
        }

        private ExecutionRejectedException reject(
                Scope scope,
                ExecutionRejectedException.Reason reason,
                String message
        ) {
            metrics.rejectedTasks++;
            if (scope != null) {
                scope.metrics.rejectedTasks++;
                pluginMetrics.get(scope.pluginId).rejectedTasks++;
            }
            return new ExecutionRejectedException(reason, message);
        }

        private ExecutionRejectedException scopeClosedRejection(Scope scope) {
            return new ExecutionRejectedException(
                    ExecutionRejectedException.Reason.SCOPE_CLOSED,
                    "Execution scope closed for " + scope.key()
            );
        }

        private ExecutionMetricsSnapshot snapshot() {
            synchronized (lock) {
                return metrics.snapshot();
            }
        }

        private Map<String, ExecutionMetricsSnapshot> pluginSnapshots() {
            synchronized (lock) {
                Map<String, ExecutionMetricsSnapshot> result = new HashMap<>();
                pluginMetrics.forEach((plugin, value) -> result.put(plugin, value.snapshot()));
                return Map.copyOf(result);
            }
        }

        private Map<String, ExecutionMetricsSnapshot> connectionSnapshots() {
            synchronized (lock) {
                Map<String, ExecutionMetricsSnapshot> result = new HashMap<>();
                queues.keySet().forEach(scope -> result.put(scope.key(), scope.metrics.snapshot()));
                return Map.copyOf(result);
            }
        }

        private void changeQueued(Scope scope, int delta) {
            scope.metrics.queuedTasks += delta;
            pluginMetrics.get(scope.pluginId).queuedTasks += delta;
            metrics.queuedTasks += delta;
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
        public boolean tryAcquireSubscription() {
            return lane.tryAcquireSubscription(this);
        }

        @Override
        public void releaseSubscription() {
            lane.releaseSubscription(this);
        }

        @Override
        public void recordDroppedMessages(long count) {
            lane.recordDroppedMessages(this, count);
        }

        @Override
        public void reserveDeferredQueueSlot() {
            lane.reserveDeferredQueueSlot(this);
        }

        @Override
        public void releaseDeferredQueueSlot() {
            lane.releaseDeferredQueueSlot(this);
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
        private long activeSubscriptions;
        private long droppedMessages;
        private long totalQueueWaitNanos;
        private long maxQueueWaitNanos;
        private long totalExecutionNanos;
        private long maxExecutionNanos;

        private void recordQueueWait(long nanos) {
            totalQueueWaitNanos += nanos;
            maxQueueWaitNanos = Math.max(maxQueueWaitNanos, nanos);
        }

        private void recordExecution(long nanos, boolean failed) {
            totalExecutionNanos += nanos;
            maxExecutionNanos = Math.max(maxExecutionNanos, nanos);
            if (failed) {
                failedTasks++;
            } else {
                completedTasks++;
            }
        }

        private ExecutionMetricsSnapshot snapshot() {
            return new ExecutionMetricsSnapshot(
                    activeTasks,
                    queuedTasks,
                    completedTasks,
                    failedTasks,
                    cancelledTasks,
                    rejectedTasks,
                    activeSubscriptions,
                    droppedMessages,
                    totalQueueWaitNanos,
                    maxQueueWaitNanos,
                    totalExecutionNanos,
                    maxExecutionNanos
            );
        }
    }

    private static final class Task {
        private final Runnable command;
        private final long enqueuedAt;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private Task(Runnable command, long enqueuedAt) {
            this.command = command;
            this.enqueuedAt = enqueuedAt;
        }

        private boolean markCancelled() {
            return cancelled.compareAndSet(false, true);
        }
    }

    private record ActiveTask(Scope scope, Task task) {
    }

    public record AdmissionLimits(int perPluginQueue, int perResourceQueue) {
        public AdmissionLimits {
            if (perPluginQueue < 1 || perResourceQueue < 1) {
                throw new IllegalArgumentException("Admission queue limits must be positive.");
            }
        }
    }

    private record Selection(Scope scope, Task task) {
    }
}
