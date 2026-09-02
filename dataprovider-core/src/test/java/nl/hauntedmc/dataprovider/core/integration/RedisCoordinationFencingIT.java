package nl.hauntedmc.dataprovider.core.integration;

import nl.hauntedmc.dataprovider.core.database.keyvalue.impl.redis.RedisDatabase;
import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import nl.hauntedmc.dataprovider.database.coordination.FencedLease;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Qualifies the Redis coordination guarantees consumed by higher-level multi-process runtimes.
 *
 * <p>The tests intentionally model owners as {@code logical-node-id/process-incarnation}. A restarted
 * process must never be able to revive a former lease merely because its logical node name is unchanged.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisCoordinationFencingIT {

    private static final String REDIS_PASSWORD = "coordination-secret";
    private static final Duration NORMAL_TTL = Duration.ofSeconds(5);

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    @Test
    void acquireRenewAndReleaseRequireExactLeaseOwnership() throws Exception {
        RedisDatabase database = database();
        try {
            database.connect();
            assertTrue(database.isConnected());
            var coordination = database.getCoordinationDataAccess();
            String resource = resource("exact-owner");
            String ownerA = owner("proxy-01");
            String ownerB = owner("proxy-02");

            FencedLease first = coordination.acquire(resource, ownerA, NORMAL_TTL).join().orElseThrow();
            assertTrue(coordination.acquire(resource, ownerB, NORMAL_TTL).join().isEmpty());

            Thread.sleep(20L);
            FencedLease renewed = coordination.renew(first, NORMAL_TTL).join().orElseThrow();
            assertEquals(first.owner(), renewed.owner());
            assertEquals(first.fencingToken(), renewed.fencingToken());
            assertTrue(renewed.expiresAt().isAfter(first.expiresAt()));

            FencedLease wrongOwner = new FencedLease(
                    resource, ownerB, renewed.fencingToken(), renewed.expiresAt());
            assertTrue(coordination.renew(wrongOwner, NORMAL_TTL).join().isEmpty());
            assertFalse(coordination.release(wrongOwner).join());

            FencedLease wrongToken = new FencedLease(
                    resource, ownerA, renewed.fencingToken() + 1_000L, renewed.expiresAt());
            assertTrue(coordination.renew(wrongToken, NORMAL_TTL).join().isEmpty());
            assertFalse(coordination.release(wrongToken).join());

            assertTrue(coordination.release(renewed).join());
            FencedLease next = coordination.acquire(resource, ownerB, NORMAL_TTL).join().orElseThrow();
            assertTrue(next.fencingToken() > renewed.fencingToken());
            assertTrue(coordination.release(next).join());
        } finally {
            database.disconnect();
        }
    }

    @Test
    void fencingTokensStrictlyIncreaseAcrossProcessIncarnations() {
        RedisDatabase database = database();
        try {
            database.connect();
            assertTrue(database.isConnected());
            var coordination = database.getCoordinationDataAccess();
            String resource = resource("monotonic");
            List<Long> tokens = new ArrayList<>();

            for (String logicalNode : List.of("proxy-01", "proxy-02", "proxy-01", "proxy-03")) {
                FencedLease lease = coordination.acquire(resource, owner(logicalNode), NORMAL_TTL)
                        .join().orElseThrow();
                tokens.add(lease.fencingToken());
                assertTrue(coordination.release(lease).join());
            }

            for (int index = 1; index < tokens.size(); index++) {
                assertTrue(tokens.get(index) > tokens.get(index - 1),
                        () -> "Fencing tokens must be strictly increasing: " + tokens);
            }
        } finally {
            database.disconnect();
        }
    }

    @Test
    void authoritativeClaimImmediatelyFencesTheFormerOwner() {
        RedisDatabase database = database();
        try {
            database.connect();
            assertTrue(database.isConnected());
            var coordination = database.getCoordinationDataAccess();
            String resource = resource("claim");
            String ownerA = owner("proxy-01");
            String ownerB = owner("proxy-02");

            FencedLease first = coordination.acquire(resource, ownerA, NORMAL_TTL).join().orElseThrow();
            var claim = coordination.claim(resource, ownerB, NORMAL_TTL).join();
            FencedLease second = claim.lease();

            assertEquals(ownerA, claim.previousOwner().orElseThrow());
            assertEquals(first.fencingToken(), claim.previousFencingToken());
            assertTrue(second.fencingToken() > first.fencingToken());
            assertTrue(coordination.renew(first, NORMAL_TTL).join().isEmpty());
            assertFalse(coordination.release(first).join());
            assertTrue(coordination.renew(second, NORMAL_TTL).join().isPresent());
            assertTrue(coordination.release(second).join());
        } finally {
            database.disconnect();
        }
    }

    @Test
    void staleWriterCannotOverwriteOrDeleteNewerOwnersValue() {
        RedisDatabase database = database();
        try {
            database.connect();
            assertTrue(database.isConnected());
            var coordination = database.getCoordinationDataAccess();
            var values = database.getDataAccess();
            String resource = resource("stale-writer");
            String valueKey = "coordination:test:value:" + UUID.randomUUID();

            FencedLease first = coordination.acquire(resource, owner("proxy-01"), NORMAL_TTL)
                    .join().orElseThrow();
            assertTrue(coordination.writeFenced(first, valueKey, "generation-a", NORMAL_TTL).join());
            assertEquals("generation-a", values.getKey(valueKey).join());

            FencedLease second = coordination.claim(resource, owner("proxy-02"), NORMAL_TTL).join().lease();
            assertTrue(second.fencingToken() > first.fencingToken());
            assertTrue(coordination.writeFenced(second, valueKey, "generation-b", NORMAL_TTL).join());

            assertFalse(coordination.writeFenced(first, valueKey, "stale-generation", NORMAL_TTL).join());
            assertFalse(coordination.deleteFenced(first, valueKey).join());
            assertEquals("generation-b", values.getKey(valueKey).join());

            assertTrue(coordination.deleteFenced(second, valueKey).join());
            assertEquals(null, values.getKey(valueKey).join());
            assertTrue(coordination.release(second).join());
        } finally {
            database.disconnect();
        }
    }

    @Test
    void reconnectAfterLeaseExpiryDoesNotResurrectOldProcessAuthority() throws Exception {
        RedisDatabase database = database();
        try {
            database.connect();
            assertTrue(database.isConnected());
            String resource = resource("reconnect");
            String valueKey = "coordination:test:reconnect:" + UUID.randomUUID();
            Duration shortTtl = Duration.ofMillis(500);

            FencedLease former = database.getCoordinationDataAccess()
                    .acquire(resource, owner("proxy-01"), shortTtl).join().orElseThrow();

            // Drop this client's connection while Redis remains authoritative and lets the lease expire.
            database.disconnect();
            Thread.sleep(800L);
            database.connect();
            assertTrue(database.isConnected());

            var coordination = database.getCoordinationDataAccess();
            FencedLease replacement = coordination.acquire(resource, owner("proxy-01"), NORMAL_TTL)
                    .join().orElseThrow();

            assertTrue(replacement.fencingToken() > former.fencingToken());
            assertTrue(coordination.renew(former, NORMAL_TTL).join().isEmpty());
            assertFalse(coordination.writeFenced(former, valueKey, "stale", NORMAL_TTL).join());
            assertTrue(coordination.writeFenced(replacement, valueKey, "current", NORMAL_TTL).join());
            assertEquals("current", database.getDataAccess().getKey(valueKey).join());
            assertTrue(coordination.release(replacement).join());
        } finally {
            database.disconnect();
        }
    }

    private static RedisDatabase database() {
        CommentedConfigurationNode config = CommentedConfigurationNode.root();
        try {
            config.node("host").set(REDIS.getHost());
            config.node("port").set(REDIS.getMappedPort(6379));
            config.node("password").set(REDIS_PASSWORD);
            config.node("database").set(0);
            config.node("network_namespace").set("coordination-fencing-it");
        } catch (org.spongepowered.configurate.serialize.SerializationException exception) {
            throw new IllegalStateException("Could not build Redis integration configuration.", exception);
        }
        return new RedisDatabase(config, new RecordingLoggerAdapter());
    }

    private static String resource(String purpose) {
        return "featureframework:test:" + purpose + ':' + UUID.randomUUID();
    }

    private static String owner(String logicalNodeId) {
        return logicalNodeId + '/' + UUID.randomUUID();
    }
}
