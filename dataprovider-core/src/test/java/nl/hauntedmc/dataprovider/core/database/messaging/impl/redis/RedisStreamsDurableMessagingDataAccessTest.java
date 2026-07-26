package nl.hauntedmc.dataprovider.core.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionMetricsSnapshot;
import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionState;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisDataException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("deprecation")
class RedisStreamsDurableMessagingDataAccessTest {

    @Test
    void authenticationFailureIsTerminalAndCompletesTheHandleExceptionally() throws Exception {
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenThrow(new JedisAccessControlException("WRONGPASS invalid username-password pair"));
        CountingExecution execution = new CountingExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        RedisStreamsDurableMessagingDataAccess access = access(pool, execution, logger, 20L, 20L, 0.0D, 0);

        var subscription = access.consume("terminal-stream", "terminal-group", "terminal-consumer",
                "test.terminal", TestEvent.class, ignored -> { });

        await(() -> subscription.state() == SubscriptionState.FAILED);
        assertThrows(CompletionException.class, subscription.completion()::join);
        assertEquals(0L, subscription.snapshot().reconnectCount());
        assertTrue(subscription.snapshot().lastFailure().contains("WRONGPASS"));
        assertEquals(1, execution.releasedSubscriptions.get());
        assertEquals(0, logger.warnMessages().size());
        assertEquals(1, logger.errorMessages().size());
    }

    @Test
    void transientOutageUsesBackoffAndRateLimitsWarnings() throws Exception {
        JedisPool pool = mock(JedisPool.class);
        List<Long> attempts = new ArrayList<>();
        when(pool.getResource()).thenAnswer(ignored -> {
            synchronized (attempts) {
                attempts.add(System.nanoTime());
            }
            throw new IllegalStateException("Redis durable messaging pool is temporarily unavailable.");
        });
        CountingExecution execution = new CountingExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        RedisStreamsDurableMessagingDataAccess access = access(pool, execution, logger, 30L, 30L, 0.0D, 0);

        var subscription = access.consume("outage-stream", "outage-group", "outage-consumer",
                "test.outage", TestEvent.class, ignored -> { });

        await(() -> {
            synchronized (attempts) {
                return attempts.size() >= 3;
            }
        });
        List<Long> recorded;
        synchronized (attempts) {
            recorded = List.copyOf(attempts);
        }
        assertTrue(recorded.get(1) - recorded.get(0) >= TimeUnit.MILLISECONDS.toNanos(20L));
        assertTrue(recorded.get(2) - recorded.get(1) >= TimeUnit.MILLISECONDS.toNanos(20L));
        assertEquals(SubscriptionState.RECONNECTING, subscription.state());
        assertTrue(subscription.snapshot().reconnectCount() >= 2L);
        assertEquals(1, logger.warnMessages().size());

        subscription.closeAsync().get(1, TimeUnit.SECONDS);
        assertEquals(SubscriptionState.CLOSED, subscription.state());
        assertEquals(1, execution.releasedSubscriptions.get());
    }

    @Test
    void deterministicRedisCommandFailureIsTerminal() throws Exception {
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenThrow(new JedisDataException("ERR unknown command 'XAUTOCLAIM'"));
        CountingExecution execution = new CountingExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        RedisStreamsDurableMessagingDataAccess access = access(pool, execution, logger, 20L, 20L, 0.0D, 0);

        var subscription = access.consume("invalid-stream", "invalid-group", "invalid-consumer",
                "test.invalid", TestEvent.class, ignored -> { });

        await(() -> subscription.state() == SubscriptionState.FAILED);
        assertThrows(CompletionException.class, subscription.completion()::join);
        assertEquals(0L, subscription.snapshot().reconnectCount());
        assertTrue(subscription.snapshot().lastFailure().contains("unknown command"));
        assertEquals(1, execution.releasedSubscriptions.get());
    }

    @Test
    void closeWakesAConsumerWaitingForLongReconnectBackoff() throws Exception {
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenThrow(new IllegalStateException("Redis durable messaging pool is temporarily unavailable."));
        CountingExecution execution = new CountingExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        RedisStreamsDurableMessagingDataAccess access = access(
                pool, execution, logger, TimeUnit.MINUTES.toMillis(1L), TimeUnit.MINUTES.toMillis(1L), 0.0D, 0
        );

        var subscription = access.consume("close-stream", "close-group", "close-consumer",
                "test.close", TestEvent.class, ignored -> { });
        await(() -> subscription.state() == SubscriptionState.RECONNECTING);

        long startedAt = System.nanoTime();
        subscription.closeAsync().get(1, TimeUnit.SECONDS);
        assertTrue(System.nanoTime() - startedAt < TimeUnit.MILLISECONDS.toNanos(500L));
        assertEquals(SubscriptionState.CLOSED, subscription.state());
        assertEquals(1, execution.releasedSubscriptions.get());
    }

    private static RedisStreamsDurableMessagingDataAccess access(
            JedisPool pool, ExecutionHandle execution, RecordingLoggerAdapter logger,
            long initialBackoffMs, long maxBackoffMs, double jitter, int maxAttempts
    ) {
        return new RedisStreamsDurableMessagingDataAccess(
                () -> pool, execution, logger, new MessageRegistry(logger), 1_024,
                8, 100, 1_000, 8, 60_000, 1_000, 60, 1_000, 1_000,
                initialBackoffMs, maxBackoffMs, jitter, maxAttempts
        );
    }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (!condition.evaluate() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5L);
        }
        assertTrue(condition.evaluate());
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
        @Override public boolean tryAcquireSubscription() { return activeSubscriptions.compareAndSet(0, 1); }
        @Override public void releaseSubscription() {
            activeSubscriptions.decrementAndGet();
            releasedSubscriptions.incrementAndGet();
        }
        @Override public void close() { }
    }

    private record TestEvent(String type) implements EventMessage {
        @Override public String getType() { return type; }
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }
}
