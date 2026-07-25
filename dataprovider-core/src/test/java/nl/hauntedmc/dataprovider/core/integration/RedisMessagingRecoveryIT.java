package nl.hauntedmc.dataprovider.core.integration;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.core.DataProvider;
import nl.hauntedmc.dataprovider.core.ManagedDatabaseProvider;
import nl.hauntedmc.dataprovider.core.api.DefaultDataProviderApi;
import nl.hauntedmc.dataprovider.core.identity.CallerContext;
import nl.hauntedmc.dataprovider.core.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;
import nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class RedisMessagingRecoveryIT {

    private static final String REDIS_PASSWORD = "messaging-recovery-secret";
    private static final String CHANNEL = "dataprovider.recovery.acceptance";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    @TempDir
    Path dataDirectory;

    @Test
    void originalLogicalSubscriptionSurvivesRedisProcessFailuresAndShutsDownCleanly() throws Exception {
        writeMessagingConfiguration();
        DataProvider provider = new DataProvider(
                new RecordingLoggerAdapter(),
                dataDirectory,
                getClass().getClassLoader(),
                callerResolver()
        );
        AtomicBoolean containerPaused = new AtomicBoolean(false);
        AtomicBoolean containerStopped = new AtomicBoolean(false);
        try {
            var handler = provider.getDataProviderHandler();
            DataProviderAPI api = new DefaultDataProviderApi(handler);
            MessagingDatabaseProvider messaging = (MessagingDatabaseProvider)
                    api.registerDatabaseOrThrow(DatabaseType.REDIS_MESSAGING, "default");
            ManagedDatabaseProvider recovery = (ManagedDatabaseProvider)
                    handler.requireRegisteredDatabase(DatabaseType.REDIS_MESSAGING, "default");
            var access = messaging.getDataAccess();
            LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
            Subscription subscription = access.subscribe(CHANNEL, TestEvent.class, event -> received.add(event.value()));
            String originalLogicalId = subscription.id();

            awaitState(subscription, SubscriptionState.ACTIVE);
            assertSubscriberCount(1L);
            assertDelivery(access, received, "initial");

            pause(containerPaused);
            assertFalse(recovery.recover());
            awaitState(subscription, SubscriptionState.RECONNECTING);
            resume(containerPaused);
            awaitRecovery(recovery);
            awaitState(subscription, SubscriptionState.ACTIVE);
            assertEquals(originalLogicalId, subscription.id());
            assertSubscriberCount(1L);
            assertDelivery(access, received, "after-pause");

            stop(containerStopped);
            assertFalse(recovery.recover());
            awaitState(subscription, SubscriptionState.RECONNECTING);
            start(containerStopped);
            awaitRecovery(recovery);
            awaitState(subscription, SubscriptionState.ACTIVE);
            assertEquals(originalLogicalId, subscription.id());
            assertSubscriberCount(1L);
            assertDelivery(access, received, "after-restart");

            for (int interruption = 0; interruption < 3; interruption++) {
                pause(containerPaused);
                assertFalse(recovery.recover());
                awaitState(subscription, SubscriptionState.RECONNECTING);
                resume(containerPaused);
                awaitRecovery(recovery);
                awaitState(subscription, SubscriptionState.ACTIVE);
                assertSubscriberCount(1L);
                assertDelivery(access, received, "repeated-" + interruption);
            }
            assertTrue(subscription.snapshot().reconnectCount() >= 5L);
            assertEquals(originalLogicalId, subscription.snapshot().logicalId());

            pause(containerPaused);
            assertFalse(recovery.recover());
            awaitState(subscription, SubscriptionState.RECONNECTING);
            provider.shutdownAllDatabases();
            provider = null;
            subscription.completion().get(5, TimeUnit.SECONDS);
            assertEquals(SubscriptionState.CLOSED, subscription.state());
            assertEquals(0L, countSubscriptionThreads());
            resume(containerPaused);
            awaitSubscriberCount(0L);
        } finally {
            if (containerPaused.get()) {
                resume(containerPaused);
            }
            if (containerStopped.get()) {
                start(containerStopped);
            }
            if (provider != null) {
                provider.shutdownAllDatabases();
            }
        }
    }

    private void assertDelivery(
            nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess access,
            LinkedBlockingQueue<String> received,
            String value
    ) throws Exception {
        access.publish(CHANNEL, new TestEvent("dataprovider.recovery", value)).get(5, TimeUnit.SECONDS);
        assertEquals(value, received.poll(5, TimeUnit.SECONDS));
        assertSubscriberCount(1L);
    }

    private void writeMessagingConfiguration() throws Exception {
        Files.createDirectories(dataDirectory.resolve("databases"));
        Files.writeString(dataDirectory.resolve("databases/redis_messaging.yml"), """
                default:
                  access:
                    owner_plugin: messaging-recovery-it
                    shared_with: []
                  host: %s
                  port: %d
                  password: %s
                  database: 0
                  pool:
                    connections: 2
                    min_idle: 0
                    max_idle: 2
                    max_subscriptions: 4
                    handler_batch_size: 8
                  reconnect:
                    initial_backoff_ms: 25
                    max_backoff_ms: 250
                    jitter: 0.10
                    max_attempts: 0
                  connection_timeout_ms: 500
                  socket_timeout_ms: 500
                  security:
                    max_payload_chars: 4096
                    max_queued_messages_per_handler: 64
                """.formatted(REDIS.getHost(), REDIS.getMappedPort(6379), REDIS_PASSWORD));
    }

    private static void pause(AtomicBoolean paused) {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        paused.set(true);
    }

    private static void resume(AtomicBoolean paused) {
        REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        paused.set(false);
    }

    private static void stop(AtomicBoolean stopped) {
        REDIS.getDockerClient().stopContainerCmd(REDIS.getContainerId()).withTimeout(2).exec();
        stopped.set(true);
    }

    private static void start(AtomicBoolean stopped) {
        REDIS.getDockerClient().startContainerCmd(REDIS.getContainerId()).exec();
        stopped.set(false);
    }

    private static void awaitRecovery(ManagedDatabaseProvider provider) throws Exception {
        awaitCondition(() -> provider.recover(), "Redis messaging provider did not recover.");
    }

    private static void awaitState(Subscription subscription, SubscriptionState expected) throws Exception {
        awaitCondition(() -> subscription.state() == expected,
                "Subscription did not reach " + expected + "; current=" + subscription.snapshot());
    }

    private static void assertSubscriberCount(long expected) throws Exception {
        assertEquals(expected, subscriberCount());
    }

    private static void awaitSubscriberCount(long expected) throws Exception {
        awaitCondition(() -> subscriberCount() == expected,
                "Redis did not report " + expected + " subscriber(s) for " + CHANNEL + ".");
    }

    private static long subscriberCount() {
        try (JedisPool pool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
             Jedis jedis = pool.getResource()) {
            jedis.auth(REDIS_PASSWORD);
            Map<String, Long> counts = jedis.pubsubNumSub(CHANNEL);
            return counts.getOrDefault(CHANNEL, 0L);
        }
    }

    private static long countSubscriptionThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> thread.getName().startsWith("redis-sub-dataprovider.recovery.acceptance"))
                .count();
    }

    private static void awaitCondition(CheckedCondition condition, String failureMessage) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            try {
                if (condition.evaluate()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // Backend process transitions can reject probes until Redis is accepting sockets again.
            }
            TimeUnit.MILLISECONDS.sleep(50L);
        } while (System.nanoTime() < deadline);
        assertTrue(condition.evaluate(), failureMessage);
    }

    private CallerContextResolver callerResolver() {
        return new CallerContextResolver() {
            @Override
            public CallerContext resolveCaller() {
                return new CallerContext("messaging-recovery-it", getClass().getClassLoader());
            }

            @Override
            public boolean isKnownPlugin(String pluginId) {
                return "messaging-recovery-it".equals(pluginId);
            }
        };
    }

    private record TestEvent(String type, String value) implements EventMessage {
        @Override public String getType() { return type; }
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }
}
