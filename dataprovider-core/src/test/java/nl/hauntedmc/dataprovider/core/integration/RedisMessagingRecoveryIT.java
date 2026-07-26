package nl.hauntedmc.dataprovider.core.integration;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.core.DataProvider;
import nl.hauntedmc.dataprovider.core.ManagedDatabaseProvider;
import nl.hauntedmc.dataprovider.core.resilience.ResilienceTargetAware;
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
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableEvent;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableSubscription;
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
@SuppressWarnings("deprecation")
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
                    api.registerDatabaseOrThrow(DatabaseType.REDIS_MESSAGING, "messaging-recovery");
            var access = messaging.getDataAccess();
            LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
            Subscription subscription = access.subscribe(
                    CHANNEL, "dataprovider.recovery", TestEvent.class, event -> received.add(event.value())
            );
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

    @Test
    void originalPubSubAndDurableHandlesSurvivePhysicalPoolRecreation() throws Exception {
        writeMessagingConfiguration();
        DataProvider provider = new DataProvider(
                new RecordingLoggerAdapter(), dataDirectory, getClass().getClassLoader(), callerResolver()
        );
        AtomicBoolean redisStopped = new AtomicBoolean(false);
        try {
            DataProviderAPI api = new DefaultDataProviderApi(provider.getDataProviderHandler()).forPlugin(this);
            MessagingDatabaseProvider messaging = (MessagingDatabaseProvider)
                    api.registerDatabaseOrThrow(DatabaseType.REDIS_MESSAGING, "messaging-recovery");
            ManagedDatabaseProvider recovery = (ManagedDatabaseProvider) provider.getDataProviderHandler()
                    .requireRegisteredDatabase(DatabaseType.REDIS_MESSAGING, "messaging-recovery");
            assertTrue(recovery instanceof ResilienceTargetAware,
                    "Registered Redis messaging provider must expose its shared physical resilience target.");
            ManagedDatabaseProvider physicalRecovery = ((ResilienceTargetAware) recovery).resilienceTarget();
            String suffix = Long.toUnsignedString(System.nanoTime(), 36);
            String channel = "dataprovider.pool.recreation." + suffix;
            String stream = "dataprovider.pool.recreation." + suffix;
            String group = "recreation-group-" + suffix;
            String consumer = "recreation-consumer-" + suffix;
            String type = "dataprovider.pool.recreation";
            LinkedBlockingQueue<String> pubSubReceived = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<String> durableReceived = new LinkedBlockingQueue<>();

            var access = messaging.getDataAccess();
            var durable = messaging.getDurableDataAccess();
            Subscription pubSub = access.subscribe(channel, type, TestEvent.class,
                    event -> pubSubReceived.add(event.value()));
            DurableSubscription durableSubscription = durable.consume(stream, group, consumer, type, TestEvent.class,
                    delivery -> {
                        durableReceived.add(delivery.event().payload().value());
                        delivery.acknowledge().join();
                    });
            String pubSubId = pubSub.id();
            String durableId = durableSubscription.id();
            awaitState(pubSub, SubscriptionState.ACTIVE);
            awaitCondition(() -> durableSubscription.snapshot().active(), "Durable consumer did not become active.");
            assertPubSubDelivery(access, channel, type, pubSubReceived, "before");
            assertDurableDelivery(durable, stream, type, durableReceived, "before");

            stopRedis(redisStopped);
            awaitState(pubSub, SubscriptionState.RECONNECTING);
            // The first failed probe records that the locally-open pool is unhealthy. The second
            // call must take PhysicalResource.recover()'s disconnect/connect replacement branch.
            assertTrue(!physicalRecovery.recover(), "Redis unexpectedly recovered while stopped.");
            assertTrue(!physicalRecovery.recover(), "Redis unexpectedly recreated while stopped.");

            startRedis(redisStopped);
            awaitCondition(physicalRecovery::recover, "Physical Redis resource did not recover after recreation.");
            awaitState(pubSub, SubscriptionState.ACTIVE);
            awaitCondition(() -> durableSubscription.snapshot().active(),
                    "Original durable handle did not reattach after pool recreation.");
            assertEquals(pubSubId, pubSub.id());
            assertEquals(durableId, durableSubscription.id());
            assertTrue(!pubSub.completion().isDone(), "Pool replacement closed the logical Pub/Sub handle.");
            assertTrue(!durableSubscription.completion().isDone(), "Pool replacement closed the logical durable handle.");
            assertPubSubDelivery(access, channel, type, pubSubReceived, "after");
            assertDurableDelivery(durable, stream, type, durableReceived, "after");

            pubSub.unsubscribe().get(5, TimeUnit.SECONDS);
            durableSubscription.closeAsync().get(5, TimeUnit.SECONDS);
        } finally {
            if (redisStopped.get()) startRedis(redisStopped);
            provider.shutdownAllDatabases();
        }
    }

    @Test
    void configurationReloadReplacesThePhysicalGenerationWithoutClosingLogicalSubscriptions() throws Exception {
        writeMessagingConfiguration(2);
        DataProvider provider = new DataProvider(
                new RecordingLoggerAdapter(), dataDirectory, getClass().getClassLoader(), callerResolver()
        );
        try {
            DataProviderAPI api = new DefaultDataProviderApi(provider.getDataProviderHandler()).forPlugin(this);
            MessagingDatabaseProvider messaging = (MessagingDatabaseProvider)
                    api.registerDatabaseOrThrow(DatabaseType.REDIS_MESSAGING, "messaging-recovery");
            String suffix = Long.toUnsignedString(System.nanoTime(), 36);
            String channel = "dataprovider.reload." + suffix;
            String stream = "dataprovider.reload." + suffix;
            String type = "dataprovider.reload";
            String group = "reload-group-" + suffix;
            String consumer = "reload-consumer-" + suffix;
            LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
            LinkedBlockingQueue<String> durableReceived = new LinkedBlockingQueue<>();
            Subscription subscription = messaging.getDataAccess().subscribe(channel, type, TestEvent.class,
                    event -> received.add(event.value()));
            DurableSubscription durableSubscription = messaging.getDurableDataAccess().consume(
                    stream, group, consumer, type, TestEvent.class, delivery -> {
                        durableReceived.add(delivery.event().payload().value());
                        delivery.acknowledge().join();
                    }
            );
            String logicalId = subscription.id();
            String durableLogicalId = durableSubscription.id();
            awaitState(subscription, SubscriptionState.ACTIVE);
            awaitCondition(() -> durableSubscription.snapshot().active(), "Durable consumer did not become active.");
            assertPubSubDelivery(messaging.getDataAccess(), channel, type, received, "before-reload");
            assertDurableDelivery(messaging.getDurableDataAccess(), stream, type, durableReceived, "before-reload");

            // This is a pool-setting-only change: it must still create a new physical client
            // generation while preserving the existing logical subscription handle.
            writeMessagingConfiguration(3);
            provider.getDataProviderHandler().reloadConfiguration();

            awaitState(subscription, SubscriptionState.ACTIVE);
            assertEquals(logicalId, subscription.id());
            assertEquals(durableLogicalId, durableSubscription.id());
            assertTrue(!subscription.completion().isDone(), "Reload closed the logical subscription handle.");
            assertTrue(!durableSubscription.completion().isDone(), "Reload closed the durable consumer handle.");
            awaitCondition(() -> subscriberCount(channel) == 1L,
                    "Reload did not retain exactly one subscriber for the logical subscription.");
            awaitCondition(() -> durableSubscription.snapshot().active(),
                    "Reload did not reactivate the original durable consumer.");
            assertPubSubDelivery(messaging.getDataAccess(), channel, type, received, "after-reload");
            assertDurableDelivery(messaging.getDurableDataAccess(), stream, type, durableReceived, "after-reload");
            subscription.unsubscribe().get(5, TimeUnit.SECONDS);
            durableSubscription.closeAsync().get(5, TimeUnit.SECONDS);
        } finally {
            provider.shutdownAllDatabases();
        }
    }

    @Test
    void credentialRotationReloadsThePhysicalClientAndKeepsTheOriginalSubscriptionUsable() throws Exception {
        String rotatedPassword = "messaging-recovery-rotated";
        writeMessagingConfiguration(2, REDIS_PASSWORD);
        DataProvider provider = new DataProvider(
                new RecordingLoggerAdapter(), dataDirectory, getClass().getClassLoader(), callerResolver()
        );
        try {
            DataProviderAPI api = new DefaultDataProviderApi(provider.getDataProviderHandler()).forPlugin(this);
            MessagingDatabaseProvider messaging = (MessagingDatabaseProvider)
                    api.registerDatabaseOrThrow(DatabaseType.REDIS_MESSAGING, "messaging-recovery");
            String suffix = Long.toUnsignedString(System.nanoTime(), 36);
            String channel = "dataprovider.credential-rotation." + suffix;
            String type = "dataprovider.credential-rotation";
            LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
            Subscription subscription = messaging.getDataAccess().subscribe(channel, type, TestEvent.class,
                    event -> received.add(event.value()));
            String logicalId = subscription.id();
            awaitState(subscription, SubscriptionState.ACTIVE);
            assertPubSubDelivery(messaging.getDataAccess(), channel, type, received, "before-rotation");

            try (JedisPool pool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
                 Jedis admin = pool.getResource()) {
                admin.auth(REDIS_PASSWORD);
                admin.configSet("requirepass", rotatedPassword);
            }
            writeMessagingConfiguration(2, rotatedPassword);
            provider.getDataProviderHandler().reloadConfiguration();

            awaitState(subscription, SubscriptionState.ACTIVE);
            assertEquals(logicalId, subscription.id());
            assertTrue(!subscription.completion().isDone(), "Credential rotation closed the logical handle.");
            assertPubSubDelivery(messaging.getDataAccess(), channel, type, received, "after-rotation");
            subscription.unsubscribe().get(5, TimeUnit.SECONDS);
        } finally {
            resetRedisPassword(rotatedPassword);
            provider.shutdownAllDatabases();
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

    private static void assertPubSubDelivery(
            nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess access,
            String channel,
            String type,
            LinkedBlockingQueue<String> received,
            String value
    ) throws Exception {
        access.publish(channel, new TestEvent(type, value)).get(5, TimeUnit.SECONDS);
        assertEquals(value, received.poll(5, TimeUnit.SECONDS));
    }

    private static void assertDurableDelivery(
            nl.hauntedmc.dataprovider.database.messaging.durable.DurableMessagingDataAccess access,
            String stream,
            String type,
            LinkedBlockingQueue<String> received,
            String value
    ) throws Exception {
        access.publish(stream, new DurableEvent<>("event-" + value, "key-" + value, new TestEvent(type, value)))
                .get(5, TimeUnit.SECONDS);
        assertEquals(value, received.poll(5, TimeUnit.SECONDS));
    }

    private void writeMessagingConfiguration() throws Exception {
        writeMessagingConfiguration(2);
    }

    private void writeMessagingConfiguration(int connections) throws Exception {
        writeMessagingConfiguration(connections, REDIS_PASSWORD);
    }

    private void writeMessagingConfiguration(int connections, String password) throws Exception {
        Files.createDirectories(dataDirectory.resolve("databases"));
        Files.writeString(dataDirectory.resolve("databases/redis_messaging.yml"), """
                messaging-recovery:
                  access:
                    owner_plugin: messaging-recovery-it
                    shared_with: []
                  host: %s
                  port: %d
                  password: %s
                  database: 0
                  pool:
                    connections: %d
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
                  # The durable API shares this connection, whose default blocking read is 500 ms.
                  # Leave enough headroom for the response to reach the client.
                  socket_timeout_ms: 1000
                  security:
                    max_payload_chars: 4096
                    max_queued_messages_per_handler: 64
                """.formatted(REDIS.getHost(), REDIS.getMappedPort(6379), password, connections));
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

    private static void resetRedisPassword(String currentPassword) {
        try (JedisPool pool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
             Jedis admin = pool.getResource()) {
            admin.auth(currentPassword);
            admin.configSet("requirepass", REDIS_PASSWORD);
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
        return subscriberCount(CHANNEL);
    }

    private static long subscriberCount(String channel) {
        try (JedisPool pool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379));
             Jedis jedis = pool.getResource()) {
            jedis.auth(REDIS_PASSWORD);
            Map<String, Long> counts = jedis.pubsubNumSub(channel);
            return counts.getOrDefault(channel, 0L);
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
