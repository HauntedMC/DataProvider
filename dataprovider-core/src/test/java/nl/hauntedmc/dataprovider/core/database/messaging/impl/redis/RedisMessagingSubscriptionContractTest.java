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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("deprecation")
class RedisMessagingSubscriptionContractTest {

    @Test
    void logicalSubscriptionIdsAreUniqueAcrossDifferentDestinations() throws Exception {
        JedisPool pool = mock(JedisPool.class);
        CountDownLatch listening = new CountDownLatch(2);
        CountDownLatch releaseListeners = new CountDownLatch(1);
        when(pool.getResource()).thenAnswer(invocation -> listeningJedis(null, listening, releaseListeners));

        PermissiveExecution execution = new PermissiveExecution();
        RedisMessagingDataAccess access = access(pool, execution);
        try {
            var first = access.subscribe("network.alpha", "test.event", TestEvent.class, ignored -> { });
            var second = access.subscribe("network.beta", "test.event", TestEvent.class, ignored -> { });

            assertTrue(listening.await(2, TimeUnit.SECONDS));
            await(() -> first.state() == SubscriptionState.ACTIVE && second.state() == SubscriptionState.ACTIVE);

            assertNotEquals(first.id(), second.id(),
                    "Every stable logical subscription handle must have a distinct diagnostic identity.");
            assertNotEquals(first.snapshot().logicalId(), second.snapshot().logicalId());
            assertEquals(2, access.logicalSubscriptionCount());
            assertEquals(2, execution.activeSubscriptions.get());
        } finally {
            CompletableFuture<Void> shutdown = access.shutdown();
            releaseListeners.countDown();
            shutdown.get(2, TimeUnit.SECONDS);
        }
        assertEquals(0, execution.activeSubscriptions.get());
        assertEquals(2, execution.releasedSubscriptions.get());
    }

    @Test
    void handlersOnTheSameDestinationShareOneListenerAndUnsubscribeIndependently() throws Exception {
        JedisPool pool = mock(JedisPool.class);
        AtomicReference<JedisPubSub> listener = new AtomicReference<>();
        CountDownLatch listening = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        when(pool.getResource()).thenReturn(listeningJedis(listener, listening, releaseListener));

        PermissiveExecution execution = new PermissiveExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        MessageRegistry registry = new MessageRegistry(logger);
        RedisMessagingDataAccess access = access(pool, execution, logger, registry);
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
        }
        assertEquals(0, execution.activeSubscriptions.get());
        assertEquals(1, execution.releasedSubscriptions.get());
    }

    @Test
    void messagesFromAnObsoleteListenerGenerationAreIgnoredAfterRecovery() throws Exception {
        JedisPool firstPool = mock(JedisPool.class);
        JedisPool replacementPool = mock(JedisPool.class);
        Jedis firstJedis = mock(Jedis.class);
        Jedis replacementJedis = mock(Jedis.class);
        AtomicReference<JedisPool> currentPool = new AtomicReference<>(firstPool);
        AtomicReference<JedisPubSub> obsoleteListener = new AtomicReference<>();
        AtomicReference<JedisPubSub> activeListener = new AtomicReference<>();
        CountDownLatch recovered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        when(firstPool.getResource()).thenReturn(firstJedis);
        when(replacementPool.getResource()).thenReturn(replacementJedis);
        doAnswer(invocation -> {
            JedisPubSub listener = invocation.getArgument(0);
            obsoleteListener.set(listener);
            listener.onSubscribe("network.generation", 1);
            currentPool.set(replacementPool);
            throw new IllegalStateException("simulated obsolete listener failure");
        }).when(firstJedis).subscribe(any(JedisPubSub.class), any(String[].class));
        doAnswer(invocation -> {
            JedisPubSub listener = invocation.getArgument(0);
            activeListener.set(listener);
            listener.onSubscribe("network.generation", 1);
            recovered.countDown();
            awaitLatch(releaseListener);
            return null;
        }).when(replacementJedis).subscribe(any(JedisPubSub.class), any(String[].class));

        PermissiveExecution execution = new PermissiveExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        MessageRegistry registry = new MessageRegistry(logger);
        RedisMessagingDataAccess access = new RedisMessagingDataAccess(
                currentPool::get, execution, logger, registry, 4, 1_024, 16, 8,
                0, 0, 0.0D, 0
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
        }
    }

    @Test
    void transientDispatchRejectionDropsOnlyQueuedWorkAndDoesNotWedgeTheHandler() throws Exception {
        JedisPool pool = mock(JedisPool.class);
        AtomicReference<JedisPubSub> listener = new AtomicReference<>();
        CountDownLatch listening = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        when(pool.getResource()).thenReturn(listeningJedis(listener, listening, releaseListener));

        RejectOnceExecution execution = new RejectOnceExecution();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        MessageRegistry registry = new MessageRegistry(logger);
        RedisMessagingDataAccess access = access(pool, execution, logger, registry);
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
        }
    }

    private static RedisMessagingDataAccess access(JedisPool pool, ExecutionHandle execution) {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        return access(pool, execution, logger, new MessageRegistry(logger));
    }

    private static RedisMessagingDataAccess access(
            JedisPool pool,
            ExecutionHandle execution,
            RecordingLoggerAdapter logger,
            MessageRegistry registry
    ) {
        return new RedisMessagingDataAccess(pool, execution, logger, registry, 8, 1_024, 16, 8);
    }

    private static Jedis listeningJedis(
            AtomicReference<JedisPubSub> listenerReference,
            CountDownLatch listening,
            CountDownLatch release
    ) {
        Jedis jedis = mock(Jedis.class);
        doAnswer(invocation -> {
            JedisPubSub listener = invocation.getArgument(0);
            if (listenerReference != null) {
                listenerReference.set(listener);
            }
            String[] destinations = invocation.getArgument(1);
            listener.onSubscribe(destinations[0], 1);
            listening.countDown();
            awaitLatch(release);
            return null;
        }).when(jedis).subscribe(any(JedisPubSub.class), any(String[].class));
        return jedis;
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
        private final AtomicLong droppedMessages = new AtomicLong();

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public ExecutionMetricsSnapshot metrics() {
            return new ExecutionMetricsSnapshot(
                    0, 0, 0, 0, 0, 0, activeSubscriptions.get(), droppedMessages.get(), 0, 0, 0, 0
            );
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public boolean tryAcquireSubscription() {
            activeSubscriptions.incrementAndGet();
            return true;
        }

        @Override
        public void releaseSubscription() {
            activeSubscriptions.decrementAndGet();
            releasedSubscriptions.incrementAndGet();
        }

        @Override
        public void recordDroppedMessages(long count) {
            droppedMessages.addAndGet(count);
        }

        @Override
        public void close() {
        }
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
        @Override
        public String getType() {
            return type;
        }
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }
}
