package nl.hauntedmc.dataprovider.core.resilience;

import nl.hauntedmc.dataprovider.core.ConnectionHealthSnapshot;
import nl.hauntedmc.dataprovider.core.ManagedDatabaseProvider;
import nl.hauntedmc.dataprovider.core.ProviderLifecycleState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Core-owned bounded runtime for remote health and recovery work. It never receives application
 * operations, and each tracked logical provider has no more than one active probe or recovery.
 */
public final class ResilienceRuntime implements AutoCloseable {

    private final ResilienceRuntimeConfig config;
    private final Clock clock;
    private final Random random;
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsExecutors;
    private final ConcurrentMap<Object, Control> controls = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ResilienceRuntime(ResilienceRuntimeConfig config) {
        this(config, Clock.systemUTC(), new Random(), newWorkerPool(config), newScheduler(), true);
    }

    /** Injectable dependencies make state transitions deterministic in unit tests. */
    ResilienceRuntime(
            ResilienceRuntimeConfig config,
            Clock clock,
            Random random,
            ExecutorService workers,
            ScheduledExecutorService scheduler
    ) {
        this(config, clock, random, workers, scheduler, false);
    }

    private ResilienceRuntime(
            ResilienceRuntimeConfig config,
            Clock clock,
            Random random,
            ExecutorService workers,
            ScheduledExecutorService scheduler,
            boolean ownsExecutors
    ) {
        this.config = Objects.requireNonNull(config, "Resilience configuration cannot be null.");
        this.clock = Objects.requireNonNull(clock, "Clock cannot be null.");
        this.random = Objects.requireNonNull(random, "Random cannot be null.");
        this.workers = Objects.requireNonNull(workers, "Worker executor cannot be null.");
        this.scheduler = Objects.requireNonNull(scheduler, "Scheduler cannot be null.");
        this.ownsExecutors = ownsExecutors;
    }

    public Control track(
            Object key,
            ManagedDatabaseProvider provider,
            Supplier<ProviderLifecycleState> lifecycleState
    ) {
        requireOpen();
        Object resolvedKey = Objects.requireNonNull(key, "Key cannot be null.");
        return controls.compute(resolvedKey, (ignored, existing) -> {
            if (existing != null && existing.retain()) {
                return existing;
            }
            Control created = new Control(provider, lifecycleState);
            created.start();
            return created;
        });
    }

    public void untrack(Object key) {
        controls.computeIfPresent(key, (ignored, control) -> control.release() ? null : control);
    }

    /** Cached status reads use this value and never need to parse configuration or perform I/O. */
    public Duration staleThreshold() {
        return config.staleThreshold();
    }

    public CompletableFuture<Void> requestAll() {
        CompletableFuture<?>[] requests = controls.values().stream()
                .map(Control::requestRefresh)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(requests);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        controls.values().forEach(Control::close);
        controls.clear();
        if (ownsExecutors) {
            scheduler.shutdownNow();
            workers.shutdownNow();
            long deadline = saturatedAdd(System.nanoTime(), config.shutdownGrace().toNanos());
            awaitTermination(scheduler, remainingDuration(deadline));
            awaitTermination(workers, remainingDuration(deadline));
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Resilience runtime is shut down.");
        }
    }

    private static ThreadPoolExecutor newWorkerPool(ResilienceRuntimeConfig config) {
        return new ThreadPoolExecutor(
                config.workers(),
                config.workers(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queueCapacity()),
                namedThreadFactory("dataprovider-resilience-worker"),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static ScheduledThreadPoolExecutor newScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1,
                namedThreadFactory("dataprovider-resilience-scheduler")
        );
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static void awaitTermination(ExecutorService executor, Duration timeout) {
        try {
            executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static Duration remainingDuration(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    }

    private static long saturatedAdd(long value, long increment) {
        return increment > 0 && value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    public final class Control implements AutoCloseable {

        private final Object lock = new Object();
        private final ManagedDatabaseProvider provider;
        private final Supplier<ProviderLifecycleState> lifecycleState;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ConnectionHealthSnapshot snapshot;
        private CompletableFuture<Void> activeAttempt;
        private ScheduledFuture<?> periodicTask;
        private ScheduledFuture<?> recoveryTask;
        private int registrations = 1;

        private Control(
                ManagedDatabaseProvider provider,
                Supplier<ProviderLifecycleState> lifecycleState
        ) {
            this.provider = Objects.requireNonNull(provider, "Provider cannot be null.");
            this.lifecycleState = Objects.requireNonNull(lifecycleState, "Lifecycle state supplier cannot be null.");
            snapshot = ConnectionHealthSnapshot.unprobed(provider.isLocallyConnected(), lifecycleState.get());
            if (provider instanceof ResilienceGateAware gateAware) {
                gateAware.setResilienceGate(this::acceptsWork, this::snapshot);
            }
        }

        private void start() {
            periodicTask = scheduler.scheduleWithFixedDelay(
                    this::requestRefresh,
                    config.healthInterval().toMillis(),
                    config.healthInterval().toMillis(),
                    TimeUnit.MILLISECONDS
            );
            requestRefresh();
        }

        private boolean retain() {
            synchronized (lock) {
                if (closed.get()) {
                    return false;
                }
                registrations++;
                return true;
            }
        }

        /** @return whether this was the final logical registration for the physical resource. */
        private boolean release() {
            synchronized (lock) {
                if (closed.get() || --registrations > 0) {
                    return false;
                }
            }
            close();
            return true;
        }

        public ConnectionHealthSnapshot snapshot() {
            synchronized (lock) {
                return snapshot;
            }
        }

        public boolean acceptsWork() {
            synchronized (lock) {
                return !closed.get() && snapshot.circuit() == ConnectionHealthSnapshot.Circuit.CLOSED;
            }
        }

        public CompletableFuture<Void> requestRefresh() {
            synchronized (lock) {
                if (closed.get() || ResilienceRuntime.this.closed.get()) {
                    return CompletableFuture.completedFuture(null);
                }
                if (activeAttempt != null && !activeAttempt.isDone()) {
                    return activeAttempt;
                }
                if (snapshot.circuit() == ConnectionHealthSnapshot.Circuit.OPEN) {
                    scheduleRecoveryIfNeeded(snapshot.currentBackoff());
                    return CompletableFuture.completedFuture(null);
                }
                return submitAttempt(AttemptKind.PROBE);
            }
        }

        private CompletableFuture<Void> submitAttempt(AttemptKind kind) {
            CompletableFuture<Void> completion = new CompletableFuture<>();
            activeAttempt = completion;
            try {
                workers.execute(() -> runAttempt(kind, completion));
            } catch (RejectedExecutionException exception) {
                activeAttempt = null;
                if (kind == AttemptKind.RECOVERY) {
                    recordFailure(true);
                }
                completion.complete(null);
            }
            return completion;
        }

        private void runAttempt(AttemptKind kind, CompletableFuture<Void> completion) {
            try {
                if (closed.get() || ResilienceRuntime.this.closed.get()) {
                    return;
                }
                boolean healthy;
                boolean probeError = false;
                try {
                    healthy = kind == AttemptKind.RECOVERY ? provider.recover() : provider.probeRemoteHealth();
                } catch (RuntimeException exception) {
                    healthy = false;
                    probeError = true;
                }
                synchronized (lock) {
                    if (!closed.get() && !ResilienceRuntime.this.closed.get()) {
                        if (healthy) {
                            recordSuccess(kind);
                        } else {
                            recordFailure(probeError);
                        }
                    }
                }
            } finally {
                synchronized (lock) {
                    if (activeAttempt == completion) {
                        activeAttempt = null;
                    }
                }
                completion.complete(null);
            }
        }

        private void recordSuccess(AttemptKind kind) {
            ConnectionHealthSnapshot previous = snapshot;
            int recoveries = kind == AttemptKind.RECOVERY
                    ? previous.consecutiveRecoveries() + 1
                    : previous.circuit() == ConnectionHealthSnapshot.Circuit.HALF_OPEN
                            ? previous.consecutiveRecoveries() + 1
                            : 0;
            boolean restored = previous.circuit() != ConnectionHealthSnapshot.Circuit.CLOSED
                    && recoveries >= config.recoveryThreshold();
            snapshot = new ConnectionHealthSnapshot(
                    ConnectionHealthSnapshot.LocalConnectionState.CONNECTED,
                    ConnectionHealthSnapshot.RemoteHealth.HEALTHY,
                    now(),
                    lifecycleState.get(),
                    restored || previous.circuit() == ConnectionHealthSnapshot.Circuit.CLOSED
                            ? ConnectionHealthSnapshot.RuntimeHealth.HEALTHY
                            : ConnectionHealthSnapshot.RuntimeHealth.RECOVERING,
                    restored || previous.circuit() == ConnectionHealthSnapshot.Circuit.CLOSED
                            ? ConnectionHealthSnapshot.Circuit.CLOSED
                            : ConnectionHealthSnapshot.Circuit.HALF_OPEN,
                    0,
                    recoveries,
                    null,
                    null,
                    previous.reconnectAttempts(),
                    Duration.ZERO,
                    null
            );
        }

        private void recordFailure(boolean probeError) {
            ConnectionHealthSnapshot previous = snapshot;
            int failures = previous.consecutiveFailures() + 1;
            boolean locallyConnected = provider.isLocallyConnected();
            boolean open = !locallyConnected
                    || previous.circuit() == ConnectionHealthSnapshot.Circuit.HALF_OPEN
                    || failures >= config.failureThreshold();
            Duration backoff = nextBackoff(previous.currentBackoff());
            Instant degradedSince = previous.degradedSince() == null ? now() : previous.degradedSince();
            snapshot = new ConnectionHealthSnapshot(
                    locallyConnected
                            ? ConnectionHealthSnapshot.LocalConnectionState.CONNECTED
                            : ConnectionHealthSnapshot.LocalConnectionState.DISCONNECTED,
                    probeError ? ConnectionHealthSnapshot.RemoteHealth.ERROR : ConnectionHealthSnapshot.RemoteHealth.UNHEALTHY,
                    now(),
                    lifecycleState.get(),
                    locallyConnected
                            ? ConnectionHealthSnapshot.RuntimeHealth.DEGRADED
                            : ConnectionHealthSnapshot.RuntimeHealth.UNAVAILABLE,
                    open ? ConnectionHealthSnapshot.Circuit.OPEN : ConnectionHealthSnapshot.Circuit.CLOSED,
                    failures,
                    0,
                    probeError ? "Backend health probe raised an exception." : "Backend health probe failed.",
                    degradedSince,
                    previous.reconnectAttempts(),
                    backoff,
                    open ? now().plus(backoff) : null
            );
            if (open) {
                scheduleRecoveryIfNeeded(backoff);
            }
        }

        private void scheduleRecoveryIfNeeded(Duration delay) {
            if (closed.get() || ResilienceRuntime.this.closed.get()
                    || recoveryTask != null && !recoveryTask.isDone()) {
                return;
            }
            recoveryTask = scheduler.schedule(this::startRecovery, delay.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void startRecovery() {
            synchronized (lock) {
                if (closed.get() || ResilienceRuntime.this.closed.get()
                        || activeAttempt != null && !activeAttempt.isDone()) {
                    return;
                }
                // The scheduled task is now executing. Clear its handle before the worker runs so
                // a failed HALF_OPEN attempt can schedule the next exponential-backoff recovery.
                recoveryTask = null;
                ConnectionHealthSnapshot previous = snapshot;
                snapshot = new ConnectionHealthSnapshot(
                        previous.localState(),
                        previous.remoteHealth(),
                        previous.checkedAt(),
                        lifecycleState.get(),
                        ConnectionHealthSnapshot.RuntimeHealth.RECOVERING,
                        ConnectionHealthSnapshot.Circuit.HALF_OPEN,
                        previous.consecutiveFailures(),
                        0,
                        previous.lastFailureSummary(),
                        previous.degradedSince(),
                        previous.reconnectAttempts() + 1,
                        previous.currentBackoff(),
                        null
                );
                submitAttempt(AttemptKind.RECOVERY);
            }
        }

        private Duration nextBackoff(Duration previous) {
            Duration base = previous.isZero()
                    ? config.initialBackoff()
                    : previous.multipliedBy(2).compareTo(config.maxBackoff()) > 0
                            ? config.maxBackoff()
                            : previous.multipliedBy(2);
            if (config.jitter() == 0) {
                return base;
            }
            double factor = 1 - config.jitter() + random.nextDouble() * config.jitter() * 2;
            long milliseconds = Math.max(1, Math.round(base.toMillis() * factor));
            return Duration.ofMillis(Math.min(milliseconds, config.maxBackoff().toMillis()));
        }

        private Instant now() {
            return clock.instant();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (lock) {
                if (periodicTask != null) {
                    periodicTask.cancel(true);
                }
                if (recoveryTask != null) {
                    recoveryTask.cancel(true);
                }
            }
            if (provider instanceof ResilienceGateAware gateAware) {
                gateAware.clearResilienceGate();
            }
        }
    }

    private enum AttemptKind {
        PROBE,
        RECOVERY
    }
}
