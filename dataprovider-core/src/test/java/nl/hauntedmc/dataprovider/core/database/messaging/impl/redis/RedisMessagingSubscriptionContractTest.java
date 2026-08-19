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
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.RedisProtocol;
import redis.clients.jedis.providers.PooledConnectionProvider;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RedisMessagingSubscriptionContractTest {

    @Test
    void handlersOnTheSameDestinationShareOneListenerAndUnsubscribeIndependently() throws Exception {
        Connection connection = mock(Connection.class);
        RedisClient client = client(connection);
        AtomicReference<JedisPubSub> listener = new AtomicReference<>();
        CountDownLatch listening = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        RedisMessagingDataAccess.PubSubRunner runner = listeningRunner(
                "network.shared", listener, listening, releaseListener
        );

        PermissiveExecution execution = new PermissiveExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        MessageRegistry registry = new MessageRegistry(logger);
        RedisMessagingDataAccess access = access(client, execution, logger, registry, runner);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        var first = access.subscribe("network.shared", "test.event", TestEvent.class,
                ignored -> firstCalls.incrementAndGet());
        var second = access.subscribe("network.shared", "test.event", TestEvent.class,
                ignored -> secondCalls.incrementAndGet());
        try {
            assertTrue(listening.await(2, TimeUnit.SECONDS));
            await(() -> first.state() == SubscriptionState.ACTIVE && second.state() == SubscriptionState.ACTIVE);
            assertEquals(1, access.activeListenerCount());
            assertEquals(1, access.logicalSubscriptionCount());
            assertEquals(1, execution.activeSubscriptions.get());

            String payload = registry.toJson(new TestEvent("test.event", "first"));
            listener.get().onMessage("network.shared", payload);
            assertEquals(1, firstCalls.get());
            assertEquals(1, secondCalls.get());

            first.unsubscribe().get(2, TimeUnit.SECONDS);
            assertEquals(SubscriptionState.CLOSED, first.state());
            assertEquals(SubscriptionState.ACTIVE, second.state());
            assertEquals(1, access.activeListenerCount());
            assertEquals(1, execution.activeSubscriptions.get());

            listener.get().onMessage("network.shared", payload);
            assertEquals(1, firstCalls.get());
            assertEquals(2, secondCalls.get());

            CompletableFuture<Void> stopped = second.unsubscribe();
            releaseListener.countDown();
            stopped.get(2, TimeUnit.SECONDS);
            assertEquals(SubscriptionState.CLOSED, second.state());
            assertEquals(0, access.logicalSubscriptionCount());
        } finally {
            releaseListener.countDown();
            access.shutdown().get(2, TimeUnit.SECONDS);
            client.close();
        }
        assertEquals(0, execution.activeSubscriptions.get());
        assertEquals(1, execution.releasedSubscriptions.get());
    }

    @Test
    void messagesFromAnObsoleteListenerGenerationAreIgnoredAfterRecovery() throws Exception {
        Connection firstConnection = mock(Connection.class);
        Connection replacementConnection = mock(Connection.class);
        RedisClient firstClient = client(firstConnection);
        RedisClient replacementClient = client(replacementConnection);
        AtomicReference<RedisClient> currentClient = new AtomicReference<>(firstClient);
        AtomicReference<JedisPubSub> obsoleteListener = new AtomicReference<>();
        AtomicReference<JedisPubSub> activeListener = new AtomicReference<>();
        CountDownLatch recovered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        RedisMessagingDataAccess.PubSubRunner runner = (connection, listener, destination) -> {
            listener.onSubscribe(destination, 1);
            if (connection == firstConnection) {
                obsoleteListener.set(listener);
                currentClient.set(replacementClient);
                throw new IllegalStateException("simulated obsolete listener failure");
            }
            activeListener.set(listener);
            recovered.countDown();
            awaitLatch(releaseListener);
        };

        PermissiveExecution execution = new PermissiveExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        MessageRegistry registry = new MessageRegistry(logger);
        RedisMessagingDataAccess access = new RedisMessagingDataAccess(
                currentClient::get, execution, logger, registry, 4, 1_024, 16, 8,
                0, 0, 0.0D, 0, runner
        );
        AtomicInteger calls = new AtomicInteger();
        var subscription = access.subscribe("network.generation", "test.event", TestEvent.class,
                ignored -> calls.incrementAndGet());
        try {
            assertTrue(recovered.await(2, TimeUnit.SECONDS));
            await(() -> subscription.state() == SubscriptionState.ACTIVE);
            String payload = registry.toJson(new TestEvent("test.event", "payload"));

            obsoleteListener.get().onMessage("network.generation", payload);
            assertEquals(0, calls.get(), "A fenced listener must never dispatch after replacement.");

            activeListener.get().onMessage("network.generation", payload);
            assertEquals(1, calls.get());
            assertTrue(subscription.snapshot().generation() >= 2);
        } finally {
            CompletableFuture<Void> stopped = subscription.unsubscribe();
            releaseListener.countDown();
            stopped.get(2, TimeUnit.SECONDS);
            firstClient.close();
            replacementClient.close();
        }
    }

    @Test
    void transientDispatchRejectionDropsOnlyQueuedWorkAndDoesNotWedgeTheHandler() throws Exception {
        Connection connection = mock(Connection.class);
        RedisClient client = client(connection);
        AtomicReference<JedisPubSub> listener = new AtomicReference<>();
        CountDownLatch listening = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        RedisMessagingDataAccess.PubSubRunner runner = listeningRunner(
                "network.rejection", listener, listening, releaseListener
        );

        RejectOnceExecution execution = new RejectOnceExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        MessageRegistry registry = new MessageRegistry(logger);
        RedisMessagingDataAccess access = access(client, execution, logger, registry, runner);
        CountDownLatch handled = new CountDownLatch(1);
        var subscription = access.subscribe("network.rejection", "test.event", TestEvent.class,
                ignored -> handled.countDown());
        try {
            assertTrue(listening.await(2, TimeUnit.SECONDS));
            await(() -> subscription.state() == SubscriptionState.ACTIVE);
            String payload = registry.toJson(new TestEvent("test.event", "payload"));

            listener.get().onMessage("network.rejection", payload);
            assertEquals(1, execution.droppedMessages.get());
            assertTrue(logger.warnMessages().stream().anyMatch(message -> message.contains("dispatch capacity")));

            listener.get().onMessage("network.rejection", payload);
            assertTrue(handled.await(2, TimeUnit.SECONDS),
                    "A later message must be schedulable after a transient executor rejection.");
            assertEquals(SubscriptionState.ACTIVE, subscription.state());
        } finally {
            CompletableFuture<Void> stopped = subscription.unsubscribe();
            releaseListener.countDown();
            stopped.get(2, TimeUnit.SECONDS);
            client.close();
        }
    }

    private static RedisMessagingDataAccess access(
            RedisClient client,
            ExecutionHandle execution,
            RecordingLoggerAdapter logger,
            MessageRegistry registry,
            RedisMessagingDataAccess.PubSubRunner runner
    ) {
        return new RedisMessagingDataAccess(
                () -> client, execution, logger, registry, 8, 1_024, 16, 8,
                0, 0, 0.0D, 0, runner
        );
    }

    private static RedisClient client(Connection connection) {
        BasePooledObjectFactory<Connection> factory = new BasePooledObjectFactory<>() {
            @Override public Connection create() { return connection; }
            @Override public PooledObject<Connection> wrap(Connection value) {
                return new DefaultPooledObject<>(value);
            }
            @Override public boolean validateObject(PooledObject<Connection> pooled) { return true; }
        };
        ConnectionPoolConfig poolConfig = new ConnectionPoolConfig();
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxIdle(8);
        PooledConnectionProvider provider = new PooledConnectionProvider(factory, poolConfig);
        return RedisClient.builder()
                .clientConfig(DefaultJedisClientConfig.builder().protocol(RedisProtocol.RESP3).build())
                .connectionProvider(provider)
                .build();
    }

    private static RedisMessagingDataAccess.PubSubRunner listeningRunner(
            String destination,
            AtomicReference<JedisPubSub> listenerReference,
            CountDownLatch listening,
            CountDownLatch release
    ) {
        return (connection, listener, ignoredDestination) -> {
            listenerReference.set(listener);
            listener.onSubscribe(destination, 1);
            listening.countDown();
            awaitLatch(release);
        };
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
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static class PermissiveExecution implements ExecutionHandle {
        private final AtomicInteger activeSubscriptions = new AtomicInteger();
        private final AtomicInteger releasedSubscriptions = new AtomicInteger();
        protected final AtomicLong droppedMessages = new AtomicLong();

        @Override public void execute(Runnable command) { command.run(); }
        @Override public ExecutionMetricsSnapshot metrics() {
            return new ExecutionMetricsSnapshot(
                    0, 0, 0, 0, 0, 0, activeSubscriptions.get(), droppedMessages.get(), 0, 0, 0, 0
            );
        }
        @Override public boolean isClosed() { return false; }
        @Override public boolean tryAcquireSubscription() { activeSubscriptions.incrementAndGet(); return true; }
        @Override public void releaseSubscription() {
            activeSubscriptions.decrementAndGet();
            releasedSubscriptions.incrementAndGet();
        }
        @Override public void recordDroppedMessages(long count) { droppedMessages.addAndGet(count); }
        @Override public void close() { }
    }

    private static final class RejectOnceExecution extends PermissiveExecution {
        private final AtomicBoolean rejectNext = new AtomicBoolean(true);

        @Override
        public void execute(Runnable command) {
            if (rejectNext.compareAndSet(true, false)) {
                throw new RejectedExecutionException("simulated transient saturation");
            }
            command.run();
        }
    }

    private record TestEvent(String type, String value) implements EventMessage {
        @Override public String getType() { return type; }
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }
}
