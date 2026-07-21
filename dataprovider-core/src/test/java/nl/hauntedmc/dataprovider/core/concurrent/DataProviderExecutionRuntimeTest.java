package nl.hauntedmc.dataprovider.core.concurrent;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataProviderExecutionRuntimeTest {

    @Test
    void floodedPluginCannotStarveAnotherPlugin() throws Exception {
        DataProviderExecutionRuntime runtime = runtime(new ExecutionRuntimeConfig.LaneConfig(1, 32, 1, 16, 1, 16));
        ExecutionHandle noisy = runtime.openScope("noisy", DatabaseType.MYSQL, "main");
        ExecutionHandle quiet = runtime.openScope("quiet", DatabaseType.MYSQL, "main");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<Void> blocker = AsyncTaskSupport.runAsync(noisy, "blocker", () -> {
            started.countDown();
            release.await();
            order.add("noisy-0");
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        for (int index = 1; index <= 8; index++) {
            int task = index;
            AsyncTaskSupport.runAsync(noisy, "noisy-" + task, () -> order.add("noisy-" + task));
        }
        CompletableFuture<Void> quietTask = AsyncTaskSupport.runAsync(quiet, "quiet", () -> order.add("quiet"));
        release.countDown();
        blocker.get(2, TimeUnit.SECONDS);
        quietTask.get(2, TimeUnit.SECONDS);

        assertTrue(order.indexOf("quiet") < order.indexOf("noisy-8"));
        runtime.close();
    }

    @Test
    void connectionQueueLimitReturnsExceptionalFuture() throws Exception {
        DataProviderExecutionRuntime runtime = runtime(new ExecutionRuntimeConfig.LaneConfig(1, 8, 1, 8, 1, 1));
        ExecutionHandle scope = runtime.openScope("plugin", DatabaseType.REDIS, "cache");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> active = AsyncTaskSupport.runAsync(scope, "active", () -> {
            started.countDown();
            release.await();
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        CompletableFuture<Void> queued = AsyncTaskSupport.runAsync(scope, "queued", () -> { });
        CompletableFuture<Void> rejected = AsyncTaskSupport.runAsync(scope, "rejected", () -> { });

        assertTrue(rejected.isCompletedExceptionally());
        assertEquals(1, scope.metrics().rejectedTasks());
        release.countDown();
        active.get(2, TimeUnit.SECONDS);
        queued.get(2, TimeUnit.SECONDS);
        runtime.close();
    }

    @Test
    void closingScopeFailsQueuedWorkAndRejectsNewWork() throws Exception {
        DataProviderExecutionRuntime runtime = runtime(new ExecutionRuntimeConfig.LaneConfig(1, 8, 1, 8, 1, 4));
        ExecutionHandle scope = runtime.openScope("plugin", DatabaseType.MONGODB, "documents");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        AsyncTaskSupport.runAsync(scope, "active", () -> {
            started.countDown();
            release.await();
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        CompletableFuture<Void> queued = AsyncTaskSupport.runAsync(scope, "queued", () -> { });

        Thread closer = new Thread(scope::close);
        closer.start();
        Thread.sleep(25L);
        release.countDown();
        closer.join(2_000L);

        assertTrue(queued.isCompletedExceptionally());
        assertTrue(scope.isClosed());
        CompletableFuture<Void> afterClose = AsyncTaskSupport.runAsync(scope, "after-close", () -> { });
        assertTrue(afterClose.isCompletedExceptionally());
        runtime.close();
    }

    @Test
    void messagingBudgetsAreHierarchicalAndMetricsTrackDrops() {
        DataProviderExecutionRuntime runtime = runtime(
                new ExecutionRuntimeConfig.LaneConfig(1, 8, 1, 8, 1, 4), 2, 1, 1);
        ExecutionHandle first = runtime.openScope("plugin-a", DatabaseType.REDIS_MESSAGING, "one");
        ExecutionHandle second = runtime.openScope("plugin-a", DatabaseType.REDIS_MESSAGING, "two");
        ExecutionHandle third = runtime.openScope("plugin-b", DatabaseType.REDIS_MESSAGING, "one");

        assertTrue(first.tryAcquireSubscription());
        assertFalse(second.tryAcquireSubscription());
        assertTrue(third.tryAcquireSubscription());
        assertFalse(third.tryAcquireSubscription());
        first.recordDroppedMessages(3);
        assertEquals(3, first.metrics().droppedMessages());
        assertEquals(2, runtime.metrics().get(ExecutionLane.MESSAGING).activeSubscriptions());

        first.releaseSubscription();
        assertTrue(second.tryAcquireSubscription());
        runtime.close();
        assertEquals(0, runtime.metrics().get(ExecutionLane.MESSAGING).activeSubscriptions());
    }

    @Test
    void lanesAreIsolatedAndShutdownRejectsNewScopes() throws Exception {
        DataProviderExecutionRuntime runtime = runtime(new ExecutionRuntimeConfig.LaneConfig(1, 8, 1, 8, 1, 4));
        ExecutionHandle relational = runtime.openScope("plugin", DatabaseType.MYSQL, "sql");
        ExecutionHandle redis = runtime.openScope("plugin", DatabaseType.REDIS, "redis");
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch relationalStarted = new CountDownLatch(1);
        AsyncTaskSupport.runAsync(relational, "slow", () -> {
            relationalStarted.countDown();
            release.await();
        });
        assertTrue(relationalStarted.await(2, TimeUnit.SECONDS));
        AtomicInteger completed = new AtomicInteger();
        AsyncTaskSupport.runAsync(redis, "fast", completed::incrementAndGet).get(2, TimeUnit.SECONDS);
        assertEquals(1, completed.get());
        release.countDown();
        runtime.close();
        assertThrows(ExecutionRejectedException.class,
                () -> runtime.openScope("plugin", DatabaseType.REDIS, "later"));
    }

    private static DataProviderExecutionRuntime runtime(ExecutionRuntimeConfig.LaneConfig laneConfig) {
        return runtime(laneConfig, 8, 4, 2);
    }

    private static DataProviderExecutionRuntime runtime(
            ExecutionRuntimeConfig.LaneConfig laneConfig,
            int globalSubscriptions,
            int pluginSubscriptions,
            int connectionSubscriptions
    ) {
        EnumMap<ExecutionLane, ExecutionRuntimeConfig.LaneConfig> lanes = new EnumMap<>(ExecutionLane.class);
        for (ExecutionLane lane : ExecutionLane.values()) {
            lanes.put(lane, laneConfig);
        }
        return new DataProviderExecutionRuntime(new ExecutionRuntimeConfig(
                Map.copyOf(lanes),
                Duration.ofMillis(100),
                Duration.ofMillis(500),
                globalSubscriptions,
                pluginSubscriptions,
                connectionSubscriptions
        ));
    }
}
