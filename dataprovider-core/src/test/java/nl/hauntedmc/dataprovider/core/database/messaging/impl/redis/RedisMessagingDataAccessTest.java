package nl.hauntedmc.dataprovider.core.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionMetricsSnapshot;
import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionState;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("deprecation")
class RedisMessagingDataAccessTest {

    @Test
    void reconnectsAcrossPhysicalPoolReplacementWithoutDuplicatingTheListener() throws Exception {
        JedisPool firstPool = mock(JedisPool.class);
        JedisPool replacementPool = mock(JedisPool.class);
        Jedis first = mock(Jedis.class);
        Jedis second = mock(Jedis.class);
        AtomicReference<JedisPool> currentPool = new AtomicReference<>(firstPool);
        AtomicReference<JedisPubSub> recoveredListener = new AtomicReference<>();
        CountDownLatch recovered = new CountDownLatch(1);
        CountDownLatch releaseRecoveredListener = new CountDownLatch(1);
        AtomicInteger maximumActiveListeners = new AtomicInteger();
        when(firstPool.getResource()).thenReturn(first);
        when(replacementPool.getResource()).thenReturn(second);
        doAnswer(invocation -> {
            JedisPubSub listener = invocation.getArgument(0);
            listener.onSubscribe("recover-test", 1);
            currentPool.set(replacementPool);
            throw new IllegalStateException("simulated listener loss during pool replacement");
        }).when(first).subscribe(any(JedisPubSub.class), any(String[].class));
        doAnswer(invocation -> {
            JedisPubSub listener = invocation.getArgument(0);
            recoveredListener.set(listener);
            listener.onSubscribe("recover-test", 1);
            recovered.countDown();
            releaseRecoveredListener.await(2, TimeUnit.SECONDS);
            return null;
        }).when(second).subscribe(any(JedisPubSub.class), any(String[].class));

        CountingExecution execution = new CountingExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        MessageRegistry registry = new MessageRegistry(logger);
        RedisMessagingDataAccess access = new RedisMessagingDataAccess(
                currentPool::get,
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
                0
        );
        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<TestEvent> received = new AtomicReference<>();
        var subscription = access.subscribe("recover-test", TestEvent.class, event -> {
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
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenThrow(new IllegalStateException("redis unavailable"));
        CountingExecution execution = new CountingExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        RedisMessagingDataAccess access = new RedisMessagingDataAccess(
                () -> pool,
                execution,
                logger,
                new MessageRegistry(logger),
                1,
                1_024,
                16,
                8,
                0,
                0,
                0.0D,
                1
        );

        var subscription = access.subscribe("terminal-test", TestEvent.class, ignored -> { });

        assertThrows(CompletionException.class, subscription.completion()::join);
        assertTrue(subscription.snapshot().reconnectCount() >= 1);
        assertTrue(subscription.snapshot().lastFailure().contains("redis unavailable"));
        await(() -> execution.activeSubscriptions.get() == 0);
        assertEquals(1, execution.releasedSubscriptions.get());
    }

    @Test
    void shutdownInterruptsPendingReconnectBackoffAndReleasesThePermit() throws Exception {
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenThrow(new IllegalStateException("redis unavailable"));
        CountingExecution execution = new CountingExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        RedisMessagingDataAccess access = new RedisMessagingDataAccess(
                () -> pool,
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
                0
        );
        var subscription = access.subscribe("shutdown-backoff", TestEvent.class, ignored -> { });
        await(() -> subscription.state() == SubscriptionState.RECONNECTING);

        access.shutdown().get(2, TimeUnit.SECONDS);

        assertEquals(SubscriptionState.CLOSED, subscription.state());
        assertEquals(0, execution.activeSubscriptions.get());
        assertEquals(1, execution.releasedSubscriptions.get());
    }

    @Test
    void shutdownDoesNotNeedTheSaturatedCommandExecutorToStopSubscriptions() throws Exception {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        CountDownLatch listening = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        when(pool.getResource()).thenReturn(jedis);
        doAnswer(invocation -> {
            JedisPubSub listener = invocation.getArgument(0);
            listener.onSubscribe("shutdown-test", 1);
            listening.countDown();
            releaseListener.await(2, TimeUnit.SECONDS);
            return null;
        }).when(jedis).subscribe(any(JedisPubSub.class), any(String[].class));

        RedisMessagingDataAccess access = new RedisMessagingDataAccess(
                pool,
                rejectingExecution(),
                new RecordingLoggerAdapter(),
                new MessageRegistry(new RecordingLoggerAdapter()),
                1,
                1_024,
                16,
                8
        );
        access.subscribe("shutdown-test", EventMessage.class, ignored -> { });
        assertTrue(listening.await(2, TimeUnit.SECONDS));

        var shutdown = access.shutdown();
        shutdown.get(2, TimeUnit.SECONDS);
        assertTrue(shutdown.isDone());
        releaseListener.countDown();
    }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.evaluate() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10L);
        }
        assertTrue(condition.evaluate());
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
