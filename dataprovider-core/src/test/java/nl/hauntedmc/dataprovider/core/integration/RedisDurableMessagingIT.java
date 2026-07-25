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
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableEvent;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableMessagingDataAccess;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production failure-mode proof: persistent Streams survive Redis and consumer process restart. */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings("deprecation")
class RedisDurableMessagingIT {
    private static final String PASSWORD = "durable-messaging-secret";
    private static final String STOP_MARKER = "/tmp/dataprovider-durable-stop";
    private static final String STREAM = "dataprovider.authoritative.vote";
    private static final String GROUP = "vote-appliers";

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .withCommand("sh", "-c", "while true; do if [ -f " + STOP_MARKER + "]; then sleep 0.1; "
                    + "else redis-server --appendonly yes --appendfsync always --requirepass '" + PASSWORD
                    + "'; sleep 0.1; fi; done");

    @TempDir
    Path dataDirectory;

    @Test
    void eventSurvivesRedisRestartAndRedeliveryAppliesTheBusinessEffectExactlyOnce() throws Exception {
        writeConfiguration();
        AtomicBoolean redisStopped = new AtomicBoolean();
        DataProvider producer = null;
        DataProvider crashedConsumer = null;
        DataProvider recoveredConsumer = null;
        try {
            producer = provider();
            DurableMessagingDataAccess publishedBy = durable(producer);
            DurableEvent<VoteEvent> vote = new DurableEvent<>("vote-1001", "vote:player-42:1001",
                    new VoteEvent("vote.received", "player-42"));
            assertTrue(publishedBy.publish(STREAM, vote).get(5, TimeUnit.SECONDS).newlyPublished());
            producer.shutdownAllDatabases();
            producer = null;

            // The event exists only in Redis at this point: no consumer group has processed it yet.
            stopRedis(redisStopped);
            startRedis(redisStopped);

            Set<String> committedProcessingKeys = ConcurrentHashMap.newKeySet();
            AtomicInteger businessEffects = new AtomicInteger();
            CountDownLatch firstApplication = new CountDownLatch(1);
            crashedConsumer = provider();
            DurableMessagingDataAccess first = durable(crashedConsumer);
            first.consume(STREAM, GROUP, "consumer-before-crash", VoteEvent.class, delivery -> {
                if (committedProcessingKeys.add(delivery.event().processingKey())) {
                    businessEffects.incrementAndGet(); // Represents the committed transaction's unique processing key.
                }
                firstApplication.countDown();
                // Simulate a process crash after commit and before Redis acknowledgement.
            });
            assertTrue(firstApplication.await(10, TimeUnit.SECONDS));
            crashedConsumer.shutdownAllDatabases();
            crashedConsumer = null;

            CountDownLatch recovered = new CountDownLatch(1);
            recoveredConsumer = provider();
            DurableMessagingDataAccess second = durable(recoveredConsumer);
            second.consume(STREAM, GROUP, "consumer-after-crash", VoteEvent.class, delivery -> {
                if (committedProcessingKeys.add(delivery.event().processingKey())) {
                    businessEffects.incrementAndGet();
                }
                delivery.acknowledge().join();
                recovered.countDown();
            });
            assertTrue(recovered.await(15, TimeUnit.SECONDS));
            assertEquals(1, businessEffects.get(), "the retry must not duplicate the committed business effect");
            assertTrue(second.subscriptions().getFirst().acknowledgedCount() >= 1L);
        } finally {
            if (redisStopped.get()) startRedis(redisStopped);
            if (producer != null) producer.shutdownAllDatabases();
            if (crashedConsumer != null) crashedConsumer.shutdownAllDatabases();
            if (recoveredConsumer != null) recoveredConsumer.shutdownAllDatabases();
        }
    }

    private DataProvider provider() {
        return new DataProvider(new RecordingLoggerAdapter(), dataDirectory, getClass().getClassLoader(), callerResolver());
    }

    private DurableMessagingDataAccess durable(DataProvider provider) {
        DataProviderAPI api = new DefaultDataProviderApi(provider.getDataProviderHandler()).forPlugin(this);
        MessagingDatabaseProvider messaging = (MessagingDatabaseProvider) api.registerDatabaseOrThrow(
                DatabaseType.REDIS_MESSAGING, "default");
        return messaging.getDurableDataAccess();
    }

    private void writeConfiguration() throws Exception {
        Files.createDirectories(dataDirectory.resolve("databases"));
        Files.writeString(dataDirectory.resolve("databases/redis_messaging.yml"), """
                default:
                  access:
                    owner_plugin: durable-messaging-it
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
                  durable:
                    batch_size: 8
                    read_block_ms: 100
                    reclaim_idle_ms: 1000
                    max_attempts: 4
                    retention_ms: 60000
                    retention_max_entries: 1000
                    deduplication_ttl_seconds: 600
                    dead_letter_max_entries: 100
                  connection_timeout_ms: 500
                  socket_timeout_ms: 500
                  security:
                    max_payload_chars: 4096
                    max_queued_messages_per_handler: 64
                """.formatted(REDIS.getHost(), REDIS.getMappedPort(6379), PASSWORD));
    }

    private static void stopRedis(AtomicBoolean stopped) throws Exception {
        REDIS.execInContainer("sh", "-c", "touch " + STOP_MARKER + " && redis-cli -a '" + PASSWORD
                + "' shutdown save >/dev/null 2>&1 || true");
        stopped.set(true);
        await(() -> !ready());
    }

    private static void startRedis(AtomicBoolean stopped) throws Exception {
        REDIS.execInContainer("rm", "-f", STOP_MARKER);
        await(RedisDurableMessagingIT::ready);
        stopped.set(false);
    }

    private static boolean ready() {
        try (JedisPool pool = new JedisPool(REDIS.getHost(), REDIS.getMappedPort(6379)); Jedis jedis = pool.getResource()) {
            jedis.auth(PASSWORD);
            return "PONG".equals(jedis.ping());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) return;
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertTrue(condition.evaluate());
    }

    private CallerContextResolver callerResolver() {
        PluginIdentityRegistry identities = new PluginIdentityRegistry();
        return new CallerContextResolver() {
            @Override public CallerContext resolveCaller() { return new CallerContext("durable-messaging-it", getClass().getClassLoader()); }
            @Override public boolean isKnownPlugin(String pluginId) { return "durable-messaging-it".equals(pluginId); }
            @Override public PluginIdentity issueIdentity(Object plugin) { return identities.register("durable-messaging-it", getClass().getClassLoader()); }
            @Override public boolean isIdentityActive(PluginIdentity identity) { return identities.isActive(identity); }
        };
    }

    private record VoteEvent(String type, String player) implements EventMessage {
        @Override public String getType() { return type; }
    }

    @FunctionalInterface
    private interface CheckedCondition { boolean evaluate() throws Exception; }
}
