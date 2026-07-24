package nl.hauntedmc.dataprovider.core.resilience;

import nl.hauntedmc.dataprovider.core.ManagedDatabaseProvider;
import nl.hauntedmc.dataprovider.core.ProviderLifecycleState;
import nl.hauntedmc.dataprovider.core.ConnectionHealthSnapshot;
import nl.hauntedmc.dataprovider.core.testutil.DirectExecutorService;
import nl.hauntedmc.dataprovider.database.DataAccess;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResilienceRuntimeTest {

    @Test
    void transitionsClosedOpenHalfOpenClosedWithoutTimingSleep() {
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedProvider provider = new ScriptedProvider(false, false, true);
        ResilienceRuntime runtime = runtime(new DirectExecutorService(), scheduler, 2);
        ResilienceRuntime.Control control = runtime.track("resource", provider,
                () -> ProviderLifecycleState.READY);

        assertEquals(ConnectionHealthSnapshot.Circuit.CLOSED, control.snapshot().circuit());
        control.requestRefresh().join();
        assertEquals(ConnectionHealthSnapshot.Circuit.OPEN, control.snapshot().circuit());
        assertFalse(control.acceptsWork());

        scheduler.runNext();
        assertEquals(ConnectionHealthSnapshot.Circuit.CLOSED, control.snapshot().circuit());
        assertTrue(control.acceptsWork());
        assertEquals(1, provider.recoveryCalls);

        runtime.close();
    }

    @Test
    void refreshRequestsAreCoalescedWhileProbeIsQueued() {
        ManualExecutor workers = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedProvider provider = new ScriptedProvider(true);
        ResilienceRuntime runtime = runtime(workers, scheduler, 3);
        ResilienceRuntime.Control control = runtime.track("resource", provider,
                () -> ProviderLifecycleState.READY);

        CompletableFuture<Void> first = control.requestRefresh();
        CompletableFuture<Void> second = control.requestRefresh();
        assertSame(first, second);
        assertEquals(1, workers.queuedTaskCount());

        workers.runNext();
        assertTrue(first.isDone());
        assertEquals(1, provider.probeCalls);
        runtime.close();
    }

    @Test
    void shutdownCancelsScheduledRecoveryBeforeItCanRun() {
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedProvider provider = new ScriptedProvider(false);
        ResilienceRuntime runtime = runtime(new DirectExecutorService(), scheduler, 1);
        runtime.track("resource", provider, () -> ProviderLifecycleState.READY);

        runtime.close();
        scheduler.runNext();
        assertEquals(0, provider.recoveryCalls);
    }

    @Test
    void failedHalfOpenRecoverySchedulesAnotherBackoffAttempt() {
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedProvider provider = new ScriptedProvider(false, false, true);
        ResilienceRuntime runtime = runtime(new DirectExecutorService(), scheduler, 1);
        ResilienceRuntime.Control control = runtime.track("resource", provider, () -> ProviderLifecycleState.READY);

        scheduler.runNext();
        assertEquals(ConnectionHealthSnapshot.Circuit.OPEN, control.snapshot().circuit());
        assertEquals(1, provider.recoveryCalls);

        scheduler.runNext();
        assertEquals(ConnectionHealthSnapshot.Circuit.CLOSED, control.snapshot().circuit());
        assertEquals(2, provider.recoveryCalls);
        runtime.close();
    }

    @Test
    void halfOpenStateRejectsNewWorkUntilRecoveryCompletes() {
        ManualExecutor workers = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedProvider provider = new ScriptedProvider(false, true);
        ResilienceRuntime runtime = runtime(workers, scheduler, 1);
        ResilienceRuntime.Control control = runtime.track("resource", provider, () -> ProviderLifecycleState.READY);

        workers.runNext();
        scheduler.runNext();
        assertEquals(ConnectionHealthSnapshot.Circuit.HALF_OPEN, control.snapshot().circuit());
        assertFalse(control.acceptsWork());

        workers.runNext();
        assertTrue(control.acceptsWork());
        runtime.close();
    }

    @Test
    void controllerDetachesItsGateWhenItStops() {
        ManualScheduler scheduler = new ManualScheduler();
        GateProvider provider = new GateProvider(true);
        ResilienceRuntime runtime = runtime(new DirectExecutorService(), scheduler, 3);
        runtime.track("resource", provider, () -> ProviderLifecycleState.READY);

        runtime.close();
        assertEquals(1, provider.gateClearCalls);
    }

    @Test
    void sharedPhysicalTargetHasOneProbeAndClosesOnlyAfterItsLastLogicalRegistration() {
        ManualExecutor workers = new ManualExecutor();
        ManualScheduler scheduler = new ManualScheduler();
        GateProvider provider = new GateProvider(true);
        ResilienceRuntime runtime = runtime(workers, scheduler, 3);

        ResilienceRuntime.Control first = runtime.track("physical-resource", provider,
                () -> ProviderLifecycleState.READY);
        ResilienceRuntime.Control second = runtime.track("physical-resource", provider,
                () -> ProviderLifecycleState.READY);

        assertSame(first, second);
        assertEquals(1, workers.queuedTaskCount(), "A shared pool must have one coalesced probe.");
        runtime.untrack("physical-resource");
        assertEquals(0, provider.gateClearCalls, "One remaining logical lease retains the controller.");
        runtime.untrack("physical-resource");
        assertEquals(1, provider.gateClearCalls);
        runtime.close();
    }

    @Test
    void locallyInvalidProviderOpensCircuitImmediately() {
        ManualScheduler scheduler = new ManualScheduler();
        ScriptedProvider provider = new ScriptedProvider(false) {
            @Override public boolean isConnected() { return false; }
        };
        ResilienceRuntime runtime = runtime(new DirectExecutorService(), scheduler, 3);
        ResilienceRuntime.Control control = runtime.track("resource", provider,
                () -> ProviderLifecycleState.READY);

        assertEquals(ConnectionHealthSnapshot.RuntimeHealth.UNAVAILABLE, control.snapshot().runtimeHealth());
        assertEquals(ConnectionHealthSnapshot.Circuit.OPEN, control.snapshot().circuit());
        assertFalse(control.acceptsWork());
        runtime.close();
    }

    @Test
    void probesRunOnTheDedicatedResilienceWorker() {
        ResilienceRuntimeConfig config = new ResilienceRuntimeConfig(
                1, 8, Duration.ofHours(1), Duration.ofMinutes(1), 3, 1,
                Duration.ofMillis(50), Duration.ofSeconds(1), 0, Duration.ofSeconds(1)
        );
        AtomicReference<String> threadName = new AtomicReference<>();
        ScriptedProvider provider = new ScriptedProvider(true) {
            @Override public boolean probeRemoteHealth() {
                threadName.set(Thread.currentThread().getName());
                return super.probeRemoteHealth();
            }
        };
        ResilienceRuntime runtime = new ResilienceRuntime(config);
        runtime.track("resource", provider, () -> ProviderLifecycleState.READY);
        runtime.requestAll().join();

        assertTrue(threadName.get().startsWith("dataprovider-resilience-worker"));
        runtime.close();
    }

    private static ResilienceRuntime runtime(
            java.util.concurrent.ExecutorService workers,
            ScheduledExecutorService scheduler,
            int failureThreshold
    ) {
        ResilienceRuntimeConfig config = new ResilienceRuntimeConfig(
                1, 8, Duration.ofHours(1), Duration.ofMinutes(1), failureThreshold, 1,
                Duration.ofMillis(50), Duration.ofSeconds(1), 0, Duration.ofSeconds(1)
        );
        return new ResilienceRuntime(config, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), new Random(1), workers, scheduler);
    }

    private static class ScriptedProvider implements ManagedDatabaseProvider {
        private final ArrayDeque<Boolean> probeResults = new ArrayDeque<>();
        private int probeCalls;
        private int recoveryCalls;

        private ScriptedProvider(Boolean... results) {
            probeResults.addAll(List.of(results));
        }

        @Override public boolean probeRemoteHealth() { probeCalls++; return probeResults.isEmpty() || probeResults.removeFirst(); }
        @Override public boolean recover() { recoveryCalls++; return probeRemoteHealth(); }
        @Override public boolean isConnected() { return true; }
        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public DataAccess getDataAccess() { return null; }
        @Override public DataSource getDataSource() { return null; }
    }

    private static final class GateProvider extends ScriptedProvider implements ResilienceGateAware {
        private int gateClearCalls;

        private GateProvider(Boolean... results) {
            super(results);
        }

        @Override public void setResilienceGate(
                java.util.function.BooleanSupplier gate,
                java.util.function.Supplier<ConnectionHealthSnapshot> diagnostics
        ) {
            // The runtime test only needs to verify lifecycle detachment.
        }

        @Override public void clearResilienceGate() {
            gateClearCalls++;
        }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.copyOf(tasks); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
        @Override public void execute(Runnable command) { if (shutdown) throw new IllegalStateException("shutdown"); tasks.add(command); }
        private int queuedTaskCount() { return tasks.size(); }
        private void runNext() { tasks.removeFirst().run(); }
    }

    private static final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {
        private final ArrayDeque<ManualFuture> tasks = new ArrayDeque<>();
        private boolean shutdown;
        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) { return add(command); }
        @Override @SuppressWarnings("unchecked")
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return (ScheduledFuture<V>) (ScheduledFuture<?>) add(() -> {
                try { callable.call(); } catch (Exception exception) { throw new RuntimeException(exception); }
            });
        }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) { return add(command); }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) { return new ManualFuture(command); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
        @Override public void execute(Runnable command) { add(command); }
        private <V> ManualFuture add(Runnable command) { ManualFuture future = new ManualFuture(command); tasks.add(future); return future; }
        private void runNext() { while (!tasks.isEmpty()) { ManualFuture next = tasks.removeFirst(); if (!next.isCancelled()) { next.run(); return; } } }
        private static final class ManualFuture extends FutureTask<Void> implements ScheduledFuture<Void> {
            private ManualFuture(Runnable command) { super(command, null); }
            @Override public long getDelay(TimeUnit unit) { return 0; }
            @Override public int compareTo(Delayed other) { return 0; }
        }
    }
}
