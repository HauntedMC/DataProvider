package nl.hauntedmc.dataprovider.core.concurrent;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataProviderExecutionRuntimeHardeningTest {

    @Test
    void pluginWithManyConnectionsDoesNotReceiveExtraDispatchTurns() throws Exception {
        try (DataProviderExecutionRuntime runtime = runtime(1, 64, 1, 32, 1, 16, 100)) {
            List<ExecutionHandle> noisyScopes = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                noisyScopes.add(runtime.openScope("noisy", DatabaseType.MYSQL, "connection-" + index));
            }
            ExecutionHandle quiet = runtime.openScope("quiet", DatabaseType.MYSQL, "main");
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch blockerStarted = new CountDownLatch(1);
            List<String> order = java.util.Collections.synchronizedList(new ArrayList<>());

            CompletableFuture<Void> blocker = AsyncTaskSupport.runAsync(noisyScopes.getFirst(), "blocker", () -> {
                blockerStarted.countDown();
                release.await();
                order.add("noisy-blocker");
            });
            assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));
            for (int index = 0; index < noisyScopes.size(); index++) {
                int task = index;
                AsyncTaskSupport.runAsync(noisyScopes.get(index), "noisy-" + task,
                        () -> order.add("noisy-" + task));
            }
            CompletableFuture<Void> quietTask = AsyncTaskSupport.runAsync(quiet, "quiet", () -> order.add("quiet"));

            release.countDown();
            blocker.get(2, TimeUnit.SECONDS);
            quietTask.get(2, TimeUnit.SECONDS);

            assertTrue(order.indexOf("quiet") <= 2,
                    "Plugin-first fairness should schedule quiet before noisy's many connections rotate.");
        }
    }

    @Test
    void exceptionalFuturePreservesStructuredRejectionReason() throws Exception {
        try (DataProviderExecutionRuntime runtime = runtime(1, 4, 1, 4, 1, 1, 100)) {
            ExecutionHandle scope = runtime.openScope("plugin", DatabaseType.REDIS, "cache");
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch started = new CountDownLatch(1);
            AsyncTaskSupport.runAsync(scope, "active", () -> {
                started.countDown();
                release.await();
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            AsyncTaskSupport.runAsync(scope, "queued", () -> { });
            CompletableFuture<Void> rejected = AsyncTaskSupport.runAsync(scope, "rejected", () -> { });

            CompletionException completion = org.junit.jupiter.api.Assertions.assertThrows(
                    CompletionException.class,
                    rejected::join
            );
            ExecutionRejectedException rejection = assertInstanceOf(
                    ExecutionRejectedException.class,
                    completion.getCause()
            );
            assertEquals(ExecutionRejectedException.Reason.CONNECTION_QUEUE_LIMIT, rejection.reason());
            release.countDown();
        }
    }

    @Test
    void graceExpiryCompletesActiveFutureExceptionally() throws Exception {
        try (DataProviderExecutionRuntime runtime = runtime(1, 4, 1, 4, 1, 2, 25)) {
            ExecutionHandle scope = runtime.openScope("plugin", DatabaseType.MONGODB, "documents");
            CountDownLatch started = new CountDownLatch(1);
            AtomicBoolean stop = new AtomicBoolean();
            CompletableFuture<Void> active = AsyncTaskSupport.runAsync(scope, "ignores-interrupt", () -> {
                started.countDown();
                while (!stop.get()) {
                    try {
                        Thread.sleep(5L);
                    } catch (InterruptedException ignored) {
                        // Deliberately ignore interruption to verify the API future still terminates.
                    }
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));

            scope.close();
            assertTrue(active.isCompletedExceptionally());
            assertEquals(1, scope.metrics().cancelledTasks());
            stop.set(true);
        }
    }

    @Test
    void interruptedWorkerDoesNotLeakInterruptFlagToNextTask() throws Exception {
        try (DataProviderExecutionRuntime runtime = runtime(1, 8, 1, 8, 1, 4, 25)) {
            ExecutionHandle first = runtime.openScope("first", DatabaseType.REDIS, "one");
            ExecutionHandle second = runtime.openScope("second", DatabaseType.REDIS, "two");
            CountDownLatch started = new CountDownLatch(1);
            AtomicBoolean finish = new AtomicBoolean();
            AsyncTaskSupport.runAsync(first, "slow", () -> {
                started.countDown();
                while (!finish.get()) {
                    if (Thread.interrupted()) {
                        finish.set(true);
                    }
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            first.close();

            CompletableFuture<Boolean> next = AsyncTaskSupport.supplyAsync(
                    second,
                    "check-interrupt",
                    Thread.currentThread()::isInterrupted
            );
            assertFalse(next.get(2, TimeUnit.SECONDS));
        }
    }

    private static DataProviderExecutionRuntime runtime(
            int workers,
            int queueCapacity,
            int pluginActive,
            int pluginQueue,
            int connectionActive,
            int connectionQueue,
            long scopeGraceMs
    ) {
        ExecutionRuntimeConfig.LaneConfig lane = new ExecutionRuntimeConfig.LaneConfig(
                workers,
                queueCapacity,
                pluginActive,
                pluginQueue,
                connectionActive,
                connectionQueue
        );
        EnumMap<ExecutionLane, ExecutionRuntimeConfig.LaneConfig> lanes = new EnumMap<>(ExecutionLane.class);
        for (ExecutionLane executionLane : ExecutionLane.values()) {
            lanes.put(executionLane, lane);
        }
        return new DataProviderExecutionRuntime(new ExecutionRuntimeConfig(
                Map.copyOf(lanes),
                Duration.ofMillis(scopeGraceMs),
                Duration.ofMillis(250),
                16,
                8,
                4
        ));
    }
}
