package nl.hauntedmc.dataprovider.core.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionMetricsSnapshot;
import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionState;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Connection;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.util.Pool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisMessagingDataAccessTest {

    @Test
    void reconnectsAcrossPhysicalClientReplacementWithoutDuplicatingTheListener() throws Exception {
        Connection first = mock(Connection.class);
        Connection second = mock(Connection.class);
        RedisClient firstClient = client(first);
        RedisClient replacementClient = client(second);
        AtomicReference<RedisClient> currentClient = new AtomicReference<>(firstClient);
        AtomicReference<JedisPubSub> recoveredListener = new AtomicReference<>();
        CountDownLatch recovered = new CountDownLatch(1);
        CountDownLatch releaseRecoveredListener = new CountDownLatch(1);
        AtomicInteger maximumActiveListeners = new AtomicInteger();
        RedisMessagingDataAccess.PubSubRunner runner = (connection, listener, destination) -> {
            listener.onSubscribe(destination, 1);
            if (connection == first) {
                currentClient.set(replacementClient);
                throw new IllegalStateException("simulated listener loss during client replacement");
            }
            recoveredListener.set(listener);
            recovered.countDown();
            releaseRecoveredListener.await(2, TimeUnit.SECONDS);
        };

        CountingExecution execution = new CountingExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        MessageRegistry registry = new MessageRegistry(logger);
        RedisMessagingDataAccess access = access(currentClient, execution, logger, registry, runner, 0);
        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<TestEvent> received = new AtomicReference<>();
        var subscription = access.subscribe("recover-test", "test.recovered", TestEvent.class, event -> {
            received.set(event);
            handled.countDown();
        });
        String originalId = subscription.id();

        assertTrue(recovered.await(2, TimeUnit.SECONDS));
        await(() -> subscription.state() == SubscriptionState.ACTIVE);
        maximumActiveListeners.accumulateAndGet(access.activeListenerCount(), Math::max);
        assertEquals(1, access.activeListenerCount());
        assertEquals(1, access.logicalSubscriptionCount());
        assertEquals(originalId, subscription.id());
        assertTrue(subscription.snapshot().reconnectCount() >= 1);
        assertTrue(subscription.snapshot().activeListener());

        recoveredListener.get().onMessage(
                "recover-test",
                registry.toJson(new TestEvent("test.recovered", "received"))
        );
        assertTrue(handled.await(2, TimeUnit.SECONDS));
        assertEquals("received", received.get().value());
        assertEquals(1, maximumActiveListeners.get());
        assertEquals(1, execution.activeSubscriptions.get());

        var stopped = subscription.unsubscribe();
        releaseRecoveredListener.countDown();
        stopped.get(2, TimeUnit.SECONDS);
        assertEquals(SubscriptionState.CLOSED, subscription.state());
        assertEquals(0, access.activeListenerCount());
        assertEquals(0, access.logicalSubscriptionCount());
        assertEquals(0, execution.activeSubscriptions.get());
        assertEquals(1, execution.releasedSubscriptions.get());
    }

    @Test
    void terminalReconnectFailureCompletesTheOriginalHandleExceptionally() throws Exception {
        RedisClient client = unavailableClient();
        CountingExecution execution = new CountingExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        RedisMessagingDataAccess access = access(
                new AtomicReference<>(client), execution, logger, new MessageRegistry(logger), noOpRunner(), 1
        );

        var subscription = access.subscribe("terminal-test", "test.terminal", TestEvent.class, ignored -> { });

        assertThrows(CompletionException.class, subscription.completion()::join);
        assertTrue(subscription.snapshot().reconnectCount() >= 1);
        assertTrue(subscription.snapshot().lastFailure().contains("redis unavailable"));
        await(() -> execution.activeSubscriptions.get() == 0);
        assertEquals(1, execution.releasedSubscriptions.get());
    }

    @Test
    void shutdownInterruptsPendingReconnectBackoffAndReleasesThePermit() throws Exception {
        RedisClient client = unavailableClient();
        CountingExecution execution = new CountingExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        RedisMessagingDataAccess access = new RedisMessagingDataAccess(
                () -> client,
                execution,
                logger,
                new MessageRegistry(logger),
                1,
                1_024,
                16,
                8,
                TimeUnit.MINUTES.toMillis(1),
                TimeUnit.MINUTES.toMillis(1),
                0.0D,
                0,
                noOpRunner()
        );
        var subscription = access.subscribe(
                "shutdown-backoff", "test.shutdown-backoff", TestEvent.class, ignored -> { }
        );
        await(() -> subscription.state() == SubscriptionState.RECONNECTING);

        access.shutdown().get(2, TimeUnit.SECONDS);

        assertEquals(SubscriptionState.CLOSED, subscription.state());
        assertEquals(0, execution.activeSubscriptions.get());
        assertEquals(1, execution.releasedSubscriptions.get());
    }

    @Test
    void shutdownDoesNotNeedTheSaturatedCommandExecutorToStopSubscriptions() throws Exception {
        Connection connection = mock(Connection.class);
        RedisClient client = client(connection);
        CountDownLatch listening = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        RedisMessagingDataAccess.PubSubRunner runner = (ignored, listener, destination) -> {
            listener.onSubscribe(destination, 1);
            listening.countDown();
            releaseListener.await(2, TimeUnit.SECONDS);
        };

        RedisMessagingDataAccess access = new RedisMessagingDataAccess(
                () -> client,
                rejectingExecution(),
                new RecordingLoggerAdapter(),
                new MessageRegistry(new RecordingLoggerAdapter()),
                1,
                1_024,
                16,
                8,
                250,
                10_000,
                0.20D,
                0,
                runner
        );
        access.subscribe("shutdown-test", "test.shutdown", EventMessage.class, ignored -> { });
        assertTrue(listening.await(2, TimeUnit.SECONDS));

        var shutdown = access.shutdown();
        shutdown.get(2, TimeUnit.SECONDS);
        assertTrue(shutdown.isDone());
        releaseListener.countDown();
    }

    @Test
    void repeatedConcurrentShutdownSharesOneIncompleteOperationAndReleasesTheListenerOnce() throws Exception {
        Connection connection = mock(Connection.class);
        RedisClient client = client(connection);
        CountDownLatch listening = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        RedisMessagingDataAccess.PubSubRunner runner = (ignored, listener, destination) -> {
            listener.onSubscribe(destination, 1);
            listening.countDown();
            releaseListener.await(2, TimeUnit.SECONDS);
        };

        CountingExecution execution = new CountingExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        RedisMessagingDataAccess access = access(
                new AtomicReference<>(client), execution, logger, new MessageRegistry(logger), runner, 0
        );
        var subscription = access.subscribe("concurrent-shutdown", "test.shutdown", EventMessage.class,
                ignored -> { });
        assertTrue(listening.await(2, TimeUnit.SECONDS));

        ExecutorService callers = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<CompletableFuture<Void>>> calls = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    awaitLatch(start);
                    return access.shutdown();
                }, callers));
            }
            start.countDown();
            CompletableFuture<Void> shutdown = calls.getFirst().get(2, TimeUnit.SECONDS);
            for (CompletableFuture<CompletableFuture<Void>> call : calls) {
                assertTrue(call.get(2, TimeUnit.SECONDS) == shutdown,
                        "Every caller must await the same shutdown operation.");
            }
            releaseListener.countDown();
            shutdown.get(2, TimeUnit.SECONDS);
            assertEquals(SubscriptionState.CLOSED, subscription.state());
            assertEquals(1, execution.releasedSubscriptions.get());
        } finally {
            releaseListener.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void handlerAssertionErrorDoesNotWedgeLaterDispatch() throws Exception {
        Connection connection = mock(Connection.class);
        RedisClient client = client(connection);
        AtomicReference<JedisPubSub> listener = new AtomicReference<>();
        CountDownLatch listening = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        RedisMessagingDataAccess.PubSubRunner runner = (ignored, pubSub, destination) -> {
            listener.set(pubSub);
            pubSub.onSubscribe(destination, 1);
            listening.countDown();
            releaseListener.await(2, TimeUnit.SECONDS);
        };

        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        MessageRegistry registry = new MessageRegistry(logger);
        RedisMessagingDataAccess access = access(
                new AtomicReference<>(client), new CountingExecution(), logger, registry, runner, 0
        );
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch handledSecondMessage = new CountDownLatch(1);
        access.subscribe("error-test", "test.error", TestEvent.class, ignored -> {
            if (attempts.incrementAndGet() == 1) {
                throw new AssertionError("simulated plugin failure");
            }
            handledSecondMessage.countDown();
        });
        assertTrue(listening.await(2, TimeUnit.SECONDS));

        String payload = registry.toJson(new TestEvent("test.error", "payload"));
        listener.get().onMessage("error-test", payload);
        listener.get().onMessage("error-test", payload);

        assertTrue(handledSecondMessage.await(2, TimeUnit.SECONDS));
        assertEquals(2, attempts.get());
        assertTrue(logger.errorMessages().stream().anyMatch(message -> message.contains("AssertionError")));
        access.shutdown().get(2, TimeUnit.SECONDS);
        releaseListener.countDown();
    }

    private static RedisMessagingDataAccess access(
            AtomicReference<RedisClient> client,
            ExecutionHandle execution,
            RecordingLoggerAdapter logger,
            MessageRegistry registry,
            RedisMessagingDataAccess.PubSubRunner runner,
            int maxReconnectAttempts
    ) {
        return new RedisMessagingDataAccess(
                client::get,
                execution,
                logger,
                registry,
                1,
                1_024,
                16,
                8,
                0,
                0,
                0.0D,
                maxReconnectAttempts,
                runner
        );
    }

    @SuppressWarnings("unchecked")
    private static RedisClient client(Connection connection) {
        RedisClient client = mock(RedisClient.class);
        Pool<Connection> pool = mock(Pool.class);
        when(client.getPool()).thenReturn(pool);
        when(pool.isClosed()).thenReturn(false);
        when(pool.getResource()).thenReturn(connection);
        return client;
    }

    @SuppressWarnings("unchecked")
    private static RedisClient unavailableClient() {
        RedisClient client = mock(RedisClient.class);
        Pool<Connection> pool = mock(Pool.class);
        when(client.getPool()).thenReturn(pool);
        when(pool.isClosed()).thenReturn(false);
        when(pool.getResource()).thenThrow(new IllegalStateException("redis unavailable"));
        return client;
    }

    private static RedisMessagingDataAccess.PubSubRunner noOpRunner() {
        return (connection, listener, destination) -> { };
    }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.evaluate() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        assertTrue(condition.evaluate());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrent test start.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static ExecutionHandle rejectingExecution() {
        return new ExecutionHandle() {
            @Override public void execute(Runnable command) {
                throw new AssertionError("Subscription shutdown must not use command execution capacity.");
            }
            @Override public ExecutionMetricsSnapshot metrics() {
                return new ExecutionMetricsSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            }
            @Override public boolean isClosed() { return false; }
            @Override public void close() { }
        };
    }

    private static final class CountingExecution implements ExecutionHandle {
        private final AtomicInteger activeSubscriptions = new AtomicInteger();
        private final AtomicInteger releasedSubscriptions = new AtomicInteger();

        @Override public void execute(Runnable command) { command.run(); }
        @Override public ExecutionMetricsSnapshot metrics() {
            return new ExecutionMetricsSnapshot(0, 0, 0, 0, 0, 0,
                    activeSubscriptions.get(), 0, 0, 0, 0, 0);
        }
        @Override public boolean isClosed() { return false; }
        @Override public boolean tryAcquireSubscription() {
            return activeSubscriptions.compareAndSet(0, 1);
        }
        @Override public void releaseSubscription() {
            activeSubscriptions.decrementAndGet();
            releasedSubscriptions.incrementAndGet();
        }
        @Override public void close() { }
    }

    private record TestEvent(String type, String value) implements EventMessage {
        @Override public String getType() { return type; }
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }
}
