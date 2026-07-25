package nl.hauntedmc.dataprovider.core.integration;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.core.DataProvider;
import nl.hauntedmc.dataprovider.core.api.DefaultDataProviderApi;
import nl.hauntedmc.dataprovider.core.identity.CallerContext;
import nl.hauntedmc.dataprovider.core.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityRegistry;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class RedisMessagingRecoveryIT {

    private static final String REDIS_PASSWORD = "messaging-recovery-secret";
    private static final String CHANNEL = "dataprovider.recovery.acceptance";
    private static final String REDIS_DISABLED_MARKER = "/tmp/dataprovider-redis-disabled";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .withCommand("sh", "-c", "while true; do "
                    + "if [ -f " + REDIS_DISABLED_MARKER + " ]; then sleep 0.1; "
                    + "else redis-server --requirepass '" + REDIS_PASSWORD + "'; sleep 0.1; fi; done");

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
        AtomicBoolean redisStopped = new AtomicBoolean(false);
        try {
            DataProviderAPI api = new DefaultDataProviderApi(provider.getDataProviderHandler()).forPlugin(this);
            MessagingDatabaseProvider messaging = (MessagingDatabaseProvider)
                    api.registerDatabaseOrThrow(DatabaseType.REDIS_MESSAGING, "default");
            var access = messaging.getDataAccess();
            LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
            Subscription subscription = access.subscribe(CHANNEL, TestEvent.class, event -> received.add(event.value()));
            String originalLogicalId = subscription.id();

            awaitState(subscription, SubscriptionState.ACTIVE);
            assertSubscriberCount(1L);
            assertDelivery(access, received, "initial");

            // Docker pause freezes Redis without necessarily closing an established Pub/Sub socket.
            // The same physical listener may therefore remain ACTIVE and continue after resume.
            pause(containerPaused);
            resume(containerPaused);
            awaitState(subscription, SubscriptionState.ACTIVE);
            assertEquals(originalLogicalId, subscription.id());
            assertSubscriberCount(1L);
            assertDelivery(access, received, "after-pause");

            stopRedis(redisStopped);
            awaitState(subscription, SubscriptionState.RECONNECTING);
            startRedis(redisStopped);
            awaitState(subscription, SubscriptionState.ACTIVE);
            assertEquals(originalLogicalId, subscription.id());
            assertSubscriberCount(1L);
            assertDelivery(access, received, "after-restart");

            for (int interruption = 0; interruption < 3; interruption++) {
                stopRedis(redisStopped);
                awaitState(subscription, SubscriptionState.RECONNECTING);
                startRedis(redisStopped);
                awaitState(subscription, SubscriptionState.ACTIVE);
                assertSubscriberCount(1L);
                assertDelivery(access, received, "repeated-" + interruption);
            }
            assertTrue(subscription.snapshot().reconnectCount() >= 4L);
            assertEquals(originalLogicalId, subscription.snapshot().logicalId());

            stopRedis(redisStopped);
            awaitState(subscription, SubscriptionState.RECONNECTING);
            provider.shutdownAllDatabases();
            provider = null;
            subscription.completion().get(5, TimeUnit.SECONDS);
            assertEquals(SubscriptionState.CLOSED, subscription.state());
            awaitCondition(() -> countSubscriptionThreads() == 0L,
                    "Redis subscription supervisor thread was not released after shutdown.");
            startRedis(redisStopped);
            awaitSubscriberCount(0L);
        } finally {
            if (containerPaused.get()) {
                resume(containerPaused);
            }
            if (redisStopped.get()) {
                startRedis(redisStopped);
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

    private static void stopRedis(AtomicBoolean stopped) throws Exception {
        REDIS.execInContainer("sh", "-c", "touch " + REDIS_DISABLED_MARKER
                + " && redis-cli -a '" + REDIS_PASSWORD + "' shutdown nosave >/dev/null 2>&1 || true");
        stopped.set(true);
        awaitCondition(() -> !redisReady(), "Redis server process did not stop.");
    }

    private static void startRedis(AtomicBoolean stopped) throws Exception {
        REDIS.execInContainer("rm", "-f", REDIS_DISABLED_MARKER);
        awaitCondition(RedisMessagingRecoveryIT::redisReady, "Redis server process did not restart.");
        stopped.set(false);
    }

    private static boolean redisReady() {
        try (JedisPool pool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
             Jedis jedis = pool.getResource()) {
            jedis.auth(REDIS_PASSWORD);
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (RuntimeException unavailable) {
            return false;
        }
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
        PluginIdentityRegistry identities = new PluginIdentityRegistry();
        return new CallerContextResolver() {
            @Override
            public CallerContext resolveCaller() {
                return new CallerContext("messaging-recovery-it", getClass().getClassLoader());
            }

            @Override
            public boolean isKnownPlugin(String pluginId) {
                return "messaging-recovery-it".equals(pluginId);
            }

            @Override
            public PluginIdentity issueIdentity(Object plugin) {
                return identities.register("messaging-recovery-it", getClass().getClassLoader());
            }

            @Override
            public boolean isIdentityActive(PluginIdentity identity) {
                return identities.isActive(identity);
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
