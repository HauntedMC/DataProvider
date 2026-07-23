package nl.hauntedmc.dataprovider.core.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionMetricsSnapshot;
import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisMessagingDataAccessTest {

    @Test
    void shutdownDoesNotNeedTheSaturatedCommandExecutorToStopSubscriptions() throws Exception {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        CountDownLatch listening = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        when(pool.getResource()).thenReturn(jedis);
        doAnswer(ignored -> {
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
        assertFalse(shutdown.isDone());
        releaseListener.countDown();
        shutdown.get(2, TimeUnit.SECONDS);
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
}
