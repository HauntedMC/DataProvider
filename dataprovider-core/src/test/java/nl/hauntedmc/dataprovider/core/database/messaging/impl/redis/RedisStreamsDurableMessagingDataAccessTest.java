package nl.hauntedmc.dataprovider.core.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionMetricsSnapshot;
import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionState;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Connection;
import redis.clients.jedis.ConnectionPoolConfig;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.RedisProtocol;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.executors.CommandExecutor;
import redis.clients.jedis.providers.PooledConnectionProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class RedisStreamsDurableMessagingDataAccessTest {

    @Test
    void authenticationFailureIsTerminalAndCompletesTheHandleExceptionally() throws Exception {
        TestClient testClient = client();
        doThrow(new JedisAccessControlException("WRONGPASS invalid username-password pair"))
                .when(testClient.executor()).executeCommand(any());
        CountingExecution execution = new CountingExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        RedisStreamsDurableMessagingDataAccess access = access(testClient.client(), execution, logger, 20L, 20L, 0.0D, 0);

        var subscription = access.consume("terminal-stream", "terminal-group", "terminal-consumer",
                "test.terminal", TestEvent.class, ignored -> { });

        await(() -> subscription.state() == SubscriptionState.FAILED);
        assertThrows(CompletionException.class, subscription.completion()::join);
        assertEquals(0L, subscription.snapshot().reconnectCount());
        assertTrue(subscription.snapshot().lastFailure().contains("WRONGPASS"));
        assertEquals(1, execution.releasedSubscriptions.get());
        assertEquals(0, logger.warnMessages().size());
        assertEquals(1, logger.errorMessages().size());
        testClient.close();
    }

    @Test
    void transientOutageUsesBackoffAndRateLimitsWarnings() throws Exception {
        TestClient testClient = client();
        List<Long> attempts = new ArrayList<>();
        doAnswer(ignored -> {
            synchronized (attempts) {
                attempts.add(System.nanoTime());
            }
            throw new IllegalStateException("Redis durable messaging client is temporarily unavailable.");
        }).when(testClient.executor()).executeCommand(any());
        CountingExecution execution = new CountingExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        RedisStreamsDurableMessagingDataAccess access = access(testClient.client(), execution, logger, 30L, 30L, 0.0D, 0);

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
        testClient.close();
    }

    @Test
    void deterministicRedisCommandFailureIsTerminal() throws Exception {
        TestClient testClient = client();
        doThrow(new JedisDataException("ERR unknown command 'XAUTOCLAIM'"))
                .when(testClient.executor()).executeCommand(any());
        CountingExecution execution = new CountingExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        RedisStreamsDurableMessagingDataAccess access = access(testClient.client(), execution, logger, 20L, 20L, 0.0D, 0);

        var subscription = access.consume("invalid-stream", "invalid-group", "invalid-consumer",
                "test.invalid", TestEvent.class, ignored -> { });

        await(() -> subscription.state() == SubscriptionState.FAILED);
        assertThrows(CompletionException.class, subscription.completion()::join);
        assertEquals(0L, subscription.snapshot().reconnectCount());
        assertTrue(subscription.snapshot().lastFailure().contains("unknown command"));
        assertEquals(1, execution.releasedSubscriptions.get());
        testClient.close();
    }

    @Test
    void closeWakesAConsumerWaitingForLongReconnectBackoff() throws Exception {
        TestClient testClient = client();
        doThrow(new IllegalStateException("Redis durable messaging client is temporarily unavailable."))
                .when(testClient.executor()).executeCommand(any());
        CountingExecution execution = new CountingExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        RedisStreamsDurableMessagingDataAccess access = access(
                testClient.client(), execution, logger, TimeUnit.MINUTES.toMillis(1L),
                TimeUnit.MINUTES.toMillis(1L), 0.0D, 0
        );

        var subscription = access.consume("close-stream", "close-group", "close-consumer",
                "test.close", TestEvent.class, ignored -> { });
        await(() -> subscription.state() == SubscriptionState.RECONNECTING);

        long startedAt = System.nanoTime();
        subscription.closeAsync().get(1, TimeUnit.SECONDS);
        assertTrue(System.nanoTime() - startedAt < TimeUnit.MILLISECONDS.toNanos(500L));
        assertEquals(SubscriptionState.CLOSED, subscription.state());
        assertEquals(1, execution.releasedSubscriptions.get());
        testClient.close();
    }

    @Test
    void repeatedConcurrentShutdownSharesTheDurableTeardownOperation() throws Exception {
        TestClient testClient = client();
        doThrow(new IllegalStateException("Redis durable messaging client is temporarily unavailable."))
                .when(testClient.executor()).executeCommand(any());
        CountingExecution execution = new CountingExecution();
        RedisStreamsDurableMessagingDataAccess access = access(
                testClient.client(), execution, new RecordingLoggerAdapter(), TimeUnit.MINUTES.toMillis(1L),
                TimeUnit.MINUTES.toMillis(1L), 0.0D, 0
        );
        var subscription = access.consume("concurrent-close", "close-group", "close-consumer",
                "test.close", TestEvent.class, ignored -> { });
        await(() -> subscription.state() == SubscriptionState.RECONNECTING);

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
                        "Every caller must receive the durable shutdown operation.");
            }
            shutdown.get(2, TimeUnit.SECONDS);
            assertEquals(SubscriptionState.CLOSED, subscription.state());
            assertEquals(1, execution.releasedSubscriptions.get());
        } finally {
            callers.shutdownNow();
            testClient.close();
        }
    }

    private static RedisStreamsDurableMessagingDataAccess access(
            RedisClient client, ExecutionHandle execution, RecordingLoggerAdapter logger,
            long initialBackoffMs, long maxBackoffMs, double jitter, int maxAttempts
    ) {
        return new RedisStreamsDurableMessagingDataAccess(
                () -> client, execution, logger, new MessageRegistry(logger), 1_024,
                8, 100, 1_000, 8, 60_000, 1_000, 60, 1_000, 1_000,
                initialBackoffMs, maxBackoffMs, jitter, maxAttempts
        );
    }

    private static TestClient client() {
        Connection connection = mock(Connection.class);
        BasePooledObjectFactory<Connection> factory = new BasePooledObjectFactory<>() {
            @Override public Connection create() { return connection; }
            @Override public PooledObject<Connection> wrap(Connection value) {
                return new DefaultPooledObject<>(value);
            }
            @Override public boolean validateObject(PooledObject<Connection> pooled) { return true; }
        };
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(2);
        poolConfig.setMaxIdle(2);
        PooledConnectionProvider provider = new PooledConnectionProvider(factory, poolConfig);
        CommandExecutor executor = mock(CommandExecutor.class);
        RedisClient client = RedisClient.builder()
                .clientConfig(DefaultJedisClientConfig.builder().protocol(RedisProtocol.RESP3).build())
                .connectionProvider(provider)
                .commandExecutor(executor)
                .build();
        return new TestClient(client, executor, provider);
    }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L);
        while (!condition.evaluate() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(5L);
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

    private record TestClient(
            RedisClient client,
            CommandExecutor executor,
            PooledConnectionProvider provider
    ) implements AutoCloseable {
        @Override public void close() {
            client.close();
            provider.close();
        }
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
