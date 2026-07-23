package nl.hauntedmc.dataprovider.core.concurrent;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceAdmissionTest {

    @Test
    void saturatedResourceDoesNotConsumeWorkersNeededByAnotherResource() throws Exception {
        try (DataProviderExecutionRuntime runtime = runtime(2)) {
            ResourceExecutionHandle saturated = handle(runtime, "noisy", "saturated", new ResourceAdmission(1, limits()));
            ResourceExecutionHandle idle = handle(runtime, "quiet", "idle", new ResourceAdmission(1, limits()));
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            CompletableFuture<Void> first = AsyncTaskSupport.runAsync(saturated, "first", () -> {
                started.countDown();
                release.await();
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> waitingForSameResource = AsyncTaskSupport.runAsync(saturated, "second", () -> { });

            AtomicInteger completed = new AtomicInteger();
            AsyncTaskSupport.runAsync(idle, "idle", completed::incrementAndGet).get(2, TimeUnit.SECONDS);
            assertEquals(1, completed.get());
            assertFalse(waitingForSameResource.isDone());

            release.countDown();
            CompletableFuture.allOf(first, waitingForSameResource).get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void resourceAdmissionGivesTheNextPermitToAnotherWaitingPlugin() throws Exception {
        try (DataProviderExecutionRuntime runtime = runtime(1)) {
            ResourceAdmission admission = new ResourceAdmission(1, limits());
            ResourceExecutionHandle first = handle(runtime, "first", "shared", admission);
            ResourceExecutionHandle second = handle(runtime, "second", "shared", admission);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());

            CompletableFuture<Void> active = AsyncTaskSupport.runAsync(first, "active", () -> {
                started.countDown();
                release.await();
                order.add("active");
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> firstQueued = AsyncTaskSupport.runAsync(first, "first-queued", () -> order.add("first"));
            CompletableFuture<Void> secondQueued = AsyncTaskSupport.runAsync(second, "second-queued", () -> order.add("second"));

            release.countDown();
            CompletableFuture.allOf(active, firstQueued, secondQueued).get(2, TimeUnit.SECONDS);
            assertTrue(order.indexOf("second") < order.indexOf("first"));
        }
    }

    @Test
    void jdbcPermitIsHeldUntilTheScopedConnectionIsClosed() throws Exception {
        try (DataProviderExecutionRuntime runtime = runtime(2)) {
            ResourceAdmission admission = new ResourceAdmission(1, limits());
            ResourceExecutionHandle firstHandle = handle(runtime, "first", "shared", admission);
            ResourceExecutionHandle secondHandle = handle(runtime, "second", "shared", admission);
            DataSource physical = mock(DataSource.class);
            Connection firstConnection = mock(Connection.class);
            Connection secondConnection = mock(Connection.class);
            when(physical.getConnection()).thenReturn(firstConnection, secondConnection);

            Connection held = new ExecutionDataSource(physical, firstHandle).getConnection();
            CompletableFuture<Connection> waiting = CompletableFuture.supplyAsync(() -> {
                try {
                    return new ExecutionDataSource(physical, secondHandle).getConnection();
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            Thread.sleep(100L);
            assertFalse(waiting.isDone());

            held.close();
            waiting.get(2, TimeUnit.SECONDS).close();
        }
    }

    @Test
    void subscriptionCapacityIsSharedUntilThePhysicalSubscriptionStops() {
        ResourceAdmission admission = new ResourceAdmission(1, 1, limits());
        ResourceExecutionHandle first = new ResourceExecutionHandle(
                new ContextualExecutionHandle(ExecutionHandle.direct(), "first", DatabaseType.REDIS_MESSAGING, "shared"),
                admission
        );
        ResourceExecutionHandle second = new ResourceExecutionHandle(
                new ContextualExecutionHandle(ExecutionHandle.direct(), "second", DatabaseType.REDIS_MESSAGING, "shared"),
                admission
        );

        assertTrue(first.tryAcquireSubscription());
        assertFalse(second.tryAcquireSubscription());
        first.close();
        assertFalse(second.tryAcquireSubscription());
        first.releaseSubscription();
        assertTrue(second.tryAcquireSubscription());
        second.releaseSubscription();
    }

    @Test
    void closingScopeCancelsWorkWaitingAtTheResourceGate() throws Exception {
        try (DataProviderExecutionRuntime runtime = runtime(1)) {
            ResourceAdmission admission = new ResourceAdmission(1, limits());
            ResourceExecutionHandle activeHandle = handle(runtime, "active", "shared", admission);
            ResourceExecutionHandle closingHandle = handle(runtime, "closing", "shared", admission);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            CompletableFuture<Void> active = AsyncTaskSupport.runAsync(activeHandle, "active", () -> {
                started.countDown();
                release.await();
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> waiting = AsyncTaskSupport.runAsync(closingHandle, "waiting", () -> { });

            closingHandle.close();
            assertTrue(waiting.isCompletedExceptionally());
            release.countDown();
            active.get(2, TimeUnit.SECONDS);
            assertEquals(0, runtime.metrics().get(ExecutionLane.RELATIONAL).queuedTasks());
        }
    }

    private static ResourceExecutionHandle handle(
            DataProviderExecutionRuntime runtime,
            String plugin,
            String resource,
            ResourceAdmission admission
    ) {
        ExecutionHandle raw = runtime.openScope(plugin, DatabaseType.MYSQL, resource);
        return new ResourceExecutionHandle(
                new ContextualExecutionHandle(raw, plugin, DatabaseType.MYSQL, resource),
                admission
        );
    }

    private static DataProviderExecutionRuntime runtime(int workers) {
        EnumMap<ExecutionLane, ExecutionRuntimeConfig.LaneConfig> lanes = new EnumMap<>(ExecutionLane.class);
        for (ExecutionLane lane : ExecutionLane.values()) {
            lanes.put(lane, new ExecutionRuntimeConfig.LaneConfig(workers, 16, 16, 16));
        }
        return new DataProviderExecutionRuntime(new ExecutionRuntimeConfig(
                Map.copyOf(lanes), Duration.ofMillis(100), Duration.ofMillis(250), 8, 4, 2));
    }

    private static DataProviderExecutionRuntime.AdmissionLimits limits() {
        return new DataProviderExecutionRuntime.AdmissionLimits(8, 8);
    }
}
