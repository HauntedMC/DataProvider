package nl.hauntedmc.dataprovider.core.database.keyvalue.impl.redis;

import nl.hauntedmc.dataprovider.core.concurrent.AsyncTaskSupport;
import nl.hauntedmc.dataprovider.database.coordination.CoordinationDataAccess;
import nl.hauntedmc.dataprovider.database.coordination.FencedLease;
import nl.hauntedmc.dataprovider.database.coordination.LeaseClaim;
import redis.clients.jedis.RedisClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Redis implementation whose compound operations are atomic Lua programs. */
final class RedisCoordinationDataAccess implements CoordinationDataAccess {

    private static final String ACQUIRE = """
            if redis.call('EXISTS', KEYS[1]) == 1 then return {0} end
            local token = redis.call('INCR', KEYS[2])
            local now = redis.call('TIME')
            local millis = (now[1] * 1000) + math.floor(now[2] / 1000)
            redis.call('HSET', KEYS[1], 'owner', ARGV[1], 'token', token)
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return {token, millis}
            """;
    private static final String CLAIM = """
            local previousOwner = redis.call('HGET', KEYS[1], 'owner') or ''
            local previousToken = tonumber(redis.call('HGET', KEYS[1], 'token') or '0')
            local token = redis.call('INCR', KEYS[2])
            local now = redis.call('TIME')
            local millis = (now[1] * 1000) + math.floor(now[2] / 1000)
            redis.call('HSET', KEYS[1], 'owner', ARGV[1], 'token', token)
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return {token, millis, previousOwner, previousToken}
            """;
    private static final String RENEW = """
            if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1] then return {0} end
            if tonumber(redis.call('HGET', KEYS[1], 'token') or '0') ~= tonumber(ARGV[2]) then return {0} end
            local now = redis.call('TIME')
            local millis = (now[1] * 1000) + math.floor(now[2] / 1000)
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return {1, millis}
            """;
    private static final String RELEASE = """
            if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1] then return 0 end
            if tonumber(redis.call('HGET', KEYS[1], 'token') or '0') ~= tonumber(ARGV[2]) then return 0 end
            return redis.call('DEL', KEYS[1])
            """;
    private static final String WRITE_FENCED = """
            if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1] then return 0 end
            if tonumber(redis.call('HGET', KEYS[1], 'token') or '0') ~= tonumber(ARGV[2]) then return 0 end
            redis.call('PSETEX', KEYS[2], ARGV[4], ARGV[3])
            return 1
            """;
    private static final String DELETE_FENCED = """
            if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1] then return 0 end
            if tonumber(redis.call('HGET', KEYS[1], 'token') or '0') ~= tonumber(ARGV[2]) then return 0 end
            redis.call('DEL', KEYS[2])
            return 1
            """;
    private static final String WRITE_FENCED_INDEXED = """
            if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1] then return 0 end
            if tonumber(redis.call('HGET', KEYS[1], 'token') or '0') ~= tonumber(ARGV[2]) then return 0 end
            redis.call('PSETEX', KEYS[2], ARGV[4], ARGV[3])
            redis.call('HSET', KEYS[3], ARGV[5], KEYS[2])
            return 1
            """;
    private static final String DELETE_FENCED_INDEXED = """
            if redis.call('HGET', KEYS[1], 'owner') ~= ARGV[1] then return 0 end
            if tonumber(redis.call('HGET', KEYS[1], 'token') or '0') ~= tonumber(ARGV[2]) then return 0 end
            redis.call('DEL', KEYS[2])
            redis.call('HDEL', KEYS[3], ARGV[3])
            return 1
            """;
    private static final String READ_INDEXED = """
            local members = redis.call('HGETALL', KEYS[1])
            local result = {}
            for offset = 1, #members, 2 do
              local member = members[offset]
              local key = members[offset + 1]
              local value = redis.call('GET', key)
              if value then
                table.insert(result, member)
                table.insert(result, value)
              else
                redis.call('HDEL', KEYS[1], member)
              end
            end
            return result
            """;
    private static final String COMPARE_SET_TTL = """
            local current = redis.call('GET', KEYS[1])
            if ARGV[1] == '0' then
              if current then return 0 end
            elseif current ~= ARGV[2] then
              return 0
            end
            redis.call('PSETEX', KEYS[1], ARGV[4], ARGV[3])
            return 1
            """;
    private static final String COMPARE_DELETE = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            return redis.call('DEL', KEYS[1])
            """;

    private final RedisClient redisClient;
    private final Executor executor;
    private final String prefix;

    RedisCoordinationDataAccess(RedisClient redisClient, Executor executor, String namespace) {
        this.redisClient = Objects.requireNonNull(redisClient, "Redis client cannot be null.");
        this.executor = Objects.requireNonNull(executor, "Executor cannot be null.");
        this.prefix = "dataprovider:" + requireNamespace(namespace) + ":coordination:";
    }

    @Override
    public CompletableFuture<Optional<FencedLease>> acquire(String resource, String owner, Duration ttl) {
        String normalizedResource = requireText(resource, "Resource");
        String normalizedOwner = requireText(owner, "Owner");
        long ttlMillis = requireTtl(ttl);
        return AsyncTaskSupport.supplyAsync(executor, "redis.coordination.acquire", () -> {
            List<?> result = result(redisClient.eval(ACQUIRE, keys(normalizedResource),
                    List.of(normalizedOwner, Long.toString(ttlMillis))));
            long token = number(result.getFirst());
            if (token == 0) {
                return Optional.empty();
            }
            return Optional.of(lease(normalizedResource, normalizedOwner, token, number(result.get(1)), ttlMillis));
        });
    }

    @Override
    public CompletableFuture<LeaseClaim> claim(String resource, String owner, Duration ttl) {
        String normalizedResource = requireText(resource, "Resource");
        String normalizedOwner = requireText(owner, "Owner");
        long ttlMillis = requireTtl(ttl);
        return AsyncTaskSupport.supplyAsync(executor, "redis.coordination.claim", () -> {
            List<?> result = result(redisClient.eval(CLAIM, keys(normalizedResource),
                    List.of(normalizedOwner, Long.toString(ttlMillis))));
            long token = number(result.getFirst());
            long now = number(result.get(1));
            String previousOwner = text(result.get(2));
            long previousToken = number(result.get(3));
            return new LeaseClaim(lease(normalizedResource, normalizedOwner, token, now, ttlMillis),
                    previousOwner.isEmpty() ? Optional.empty() : Optional.of(previousOwner), previousToken);
        });
    }

    @Override
    public CompletableFuture<Optional<FencedLease>> renew(FencedLease lease, Duration ttl) {
        Objects.requireNonNull(lease, "Lease cannot be null.");
        long ttlMillis = requireTtl(ttl);
        return AsyncTaskSupport.supplyAsync(executor, "redis.coordination.renew", () -> {
            List<?> result = result(redisClient.eval(RENEW, List.of(leaseKey(lease.resource())), List.of(
                    lease.owner(), Long.toString(lease.fencingToken()), Long.toString(ttlMillis))));
            if (number(result.getFirst()) == 0) {
                return Optional.empty();
            }
            return Optional.of(lease(lease.resource(), lease.owner(), lease.fencingToken(),
                    number(result.get(1)), ttlMillis));
        });
    }

    @Override
    public CompletableFuture<Boolean> release(FencedLease lease) {
        Objects.requireNonNull(lease, "Lease cannot be null.");
        return AsyncTaskSupport.supplyAsync(executor, "redis.coordination.release", () ->
                number(redisClient.eval(RELEASE, List.of(leaseKey(lease.resource())),
                        List.of(lease.owner(), Long.toString(lease.fencingToken())))) == 1);
    }

    @Override
    public CompletableFuture<Boolean> writeFenced(FencedLease lease, String key, String value, Duration ttl) {
        Objects.requireNonNull(lease, "Lease cannot be null.");
        String normalizedKey = requireText(key, "Key");
        Objects.requireNonNull(value, "Value cannot be null.");
        long ttlMillis = requireTtl(ttl);
        return AsyncTaskSupport.supplyAsync(executor, "redis.coordination.writeFenced", () ->
                number(redisClient.eval(WRITE_FENCED,
                        List.of(leaseKey(lease.resource()), normalizedKey),
                        List.of(lease.owner(), Long.toString(lease.fencingToken()), value,
                                Long.toString(ttlMillis)))) == 1);
    }

    @Override
    public CompletableFuture<Boolean> deleteFenced(FencedLease lease, String key) {
        Objects.requireNonNull(lease, "Lease cannot be null.");
        String normalizedKey = requireText(key, "Key");
        return AsyncTaskSupport.supplyAsync(executor, "redis.coordination.deleteFenced", () ->
                number(redisClient.eval(DELETE_FENCED,
                        List.of(leaseKey(lease.resource()), normalizedKey),
                        List.of(lease.owner(), Long.toString(lease.fencingToken())))) == 1);
    }

    @Override
    public CompletableFuture<Boolean> compareAndSetWithTtl(
            String key, String expectedValue, String newValue, Duration ttl
    ) {
        String normalizedKey = requireText(key, "Key");
        Objects.requireNonNull(newValue, "New value cannot be null.");
        long ttlMillis = requireTtl(ttl);
        return AsyncTaskSupport.supplyAsync(executor, "redis.coordination.compareAndSetWithTtl", () ->
                number(redisClient.eval(COMPARE_SET_TTL, List.of(normalizedKey), List.of(
                        expectedValue == null ? "0" : "1",
                        expectedValue == null ? "" : expectedValue,
                        newValue,
                        Long.toString(ttlMillis)))) == 1);
    }

    @Override
    public CompletableFuture<Boolean> compareAndDelete(String key, String expectedValue) {
        String normalizedKey = requireText(key, "Key");
        Objects.requireNonNull(expectedValue, "Expected value cannot be null.");
        return AsyncTaskSupport.supplyAsync(executor, "redis.coordination.compareAndDelete", () ->
                number(redisClient.eval(COMPARE_DELETE, List.of(normalizedKey), List.of(expectedValue))) == 1);
    }

    @Override
    public CompletableFuture<Boolean> writeFencedIndexed(
            FencedLease lease, String key, String value, Duration ttl, String index, String member
    ) {
        Objects.requireNonNull(lease, "Lease cannot be null.");
        String normalizedKey = requireText(key, "Key");
        Objects.requireNonNull(value, "Value cannot be null.");
        String normalizedIndex = requireText(index, "Index");
        String normalizedMember = requireText(member, "Member");
        long ttlMillis = requireTtl(ttl);
        return AsyncTaskSupport.supplyAsync(executor, "redis.coordination.writeFencedIndexed", () ->
                number(redisClient.eval(WRITE_FENCED_INDEXED,
                        List.of(leaseKey(lease.resource()), normalizedKey, indexKey(normalizedIndex)),
                        List.of(lease.owner(), Long.toString(lease.fencingToken()), value,
                                Long.toString(ttlMillis), normalizedMember))) == 1);
    }

    @Override
    public CompletableFuture<Boolean> deleteFencedIndexed(
            FencedLease lease, String key, String index, String member
    ) {
        Objects.requireNonNull(lease, "Lease cannot be null.");
        String normalizedKey = requireText(key, "Key");
        String normalizedIndex = requireText(index, "Index");
        String normalizedMember = requireText(member, "Member");
        return AsyncTaskSupport.supplyAsync(executor, "redis.coordination.deleteFencedIndexed", () ->
                number(redisClient.eval(DELETE_FENCED_INDEXED,
                        List.of(leaseKey(lease.resource()), normalizedKey, indexKey(normalizedIndex)),
                        List.of(lease.owner(), Long.toString(lease.fencingToken()), normalizedMember))) == 1);
    }

    @Override
    public CompletableFuture<Map<String, String>> readIndexedValues(String index) {
        String normalizedIndex = requireText(index, "Index");
        return AsyncTaskSupport.supplyAsync(executor, "redis.coordination.readIndexedValues", () -> {
            List<?> values = result(redisClient.eval(READ_INDEXED, List.of(indexKey(normalizedIndex)), List.of()));
            if ((values.size() & 1) != 0) {
                throw new IllegalStateException("Redis coordination index returned an invalid response.");
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (int offset = 0; offset < values.size(); offset += 2) {
                result.put(text(values.get(offset)), text(values.get(offset + 1)));
            }
            return Map.copyOf(result);
        });
    }

    private List<String> keys(String resource) {
        return List.of(leaseKey(resource), fenceKey(resource));
    }

    private String leaseKey(String resource) {
        return prefix + "lease:" + requireText(resource, "Resource");
    }

    private String fenceKey(String resource) {
        return prefix + "fence:" + requireText(resource, "Resource");
    }

    private String indexKey(String index) {
        return prefix + "index:" + requireText(index, "Index");
    }

    private static FencedLease lease(String resource, String owner, long token, long now, long ttlMillis) {
        return new FencedLease(resource, owner, token, Instant.ofEpochMilli(Math.addExact(now, ttlMillis)));
    }

    private static long requireTtl(Duration ttl) {
        Objects.requireNonNull(ttl, "TTL cannot be null.");
        long millis = ttl.toMillis();
        if (ttl.isZero() || ttl.isNegative() || millis < 1) {
            throw new IllegalArgumentException("TTL must be at least one millisecond.");
        }
        return millis;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be null or blank.");
        }
        return value.trim();
    }

    private static String requireNamespace(String value) {
        String normalized = requireText(value, "Namespace").toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("Namespace must match [a-z0-9._-]{1,64}.");
        }
        return normalized;
    }

    private static List<?> result(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        throw new IllegalStateException("Redis coordination script returned an invalid response.");
    }

    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(text(value));
    }

    private static String text(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return Objects.toString(value, "");
    }
}
