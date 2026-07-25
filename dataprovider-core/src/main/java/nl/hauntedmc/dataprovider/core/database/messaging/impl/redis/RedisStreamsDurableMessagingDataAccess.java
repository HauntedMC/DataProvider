package nl.hauntedmc.dataprovider.core.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.core.concurrent.AsyncTaskSupport;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionRejectedException;
import nl.hauntedmc.dataprovider.core.exception.DataProviderExceptionMapper;
import nl.hauntedmc.dataprovider.core.exception.StructuredFailures;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableDelivery;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableEvent;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableMessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableSubscription;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableSubscriptionSnapshot;
import nl.hauntedmc.dataprovider.database.messaging.durable.PublishedDurableEvent;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.params.XTrimParams;
import redis.clients.jedis.resps.StreamEntry;
import redis.clients.jedis.resps.StreamGroupInfo;
import redis.clients.jedis.resps.StreamPendingSummary;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Redis Streams implementation of the acknowledged durable messaging API. */
@SuppressWarnings("deprecation")
final class RedisStreamsDurableMessagingDataAccess implements DurableMessagingDataAccess {
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final String PUBLISH_SCRIPT = "local id=redis.call('HGET',KEYS[2],ARGV[1]);"
            + " if id then return {id,'0'} end;"
            + " id=redis.call('XADD',KEYS[1],'*','event_id',ARGV[1],'processing_key',ARGV[2],"
            + "'type',ARGV[3],'payload',ARGV[4]); redis.call('HSET',KEYS[2],ARGV[1],id);"
            + " redis.call('EXPIRE',KEYS[2],ARGV[5]); return {id,'1'};";
    private static final String ACK_SCRIPT = "local n=redis.call('XACK',KEYS[1],ARGV[1],ARGV[2]);"
            + " if n>0 then redis.call('HDEL',KEYS[2],ARGV[2]) end; return n;";
    private static final String DEAD_LETTER_SCRIPT = "local attempt=redis.call('HGET',KEYS[2],ARGV[2]) or ARGV[7];"
            + " redis.call('XADD',KEYS[3],'MAXLEN','~',ARGV[8],'*','source_stream',KEYS[1],"
            + "'source_group',ARGV[1],'source_entry_id',ARGV[2],'event_id',ARGV[3],"
            + "'processing_key',ARGV[4],'type',ARGV[5],'payload',ARGV[6],'attempt',attempt,'failure',ARGV[7]);"
            + " redis.call('XACK',KEYS[1],ARGV[1],ARGV[2]); redis.call('HDEL',KEYS[2],ARGV[2]); return 1;";

    private final Supplier<JedisPool> poolSupplier;
    private final ExecutionHandle execution;
    private final LoggerAdapter logger;
    private final MessageRegistry registry;
    private final int maxPayloadChars;
    private final int batchSize;
    private final int readBlockMs;
    private final long reclaimIdleMs;
    private final int maxAttempts;
    private final long retentionMs;
    private final long retentionMaxEntries;
    private final long deduplicationTtlSeconds;
    private final long deadLetterMaxEntries;
    private final Map<String, ConsumerLoop<?>> consumers = new ConcurrentHashMap<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    RedisStreamsDurableMessagingDataAccess(
            Supplier<JedisPool> poolSupplier,
            ExecutionHandle execution,
            LoggerAdapter logger,
            MessageRegistry registry,
            int maxPayloadChars,
            int batchSize,
            int readBlockMs,
            long reclaimIdleMs,
            int maxAttempts,
            long retentionMs,
            long retentionMaxEntries,
            long deduplicationTtlSeconds,
            long deadLetterMaxEntries
    ) {
        this.poolSupplier = Objects.requireNonNull(poolSupplier, "Pool supplier cannot be null");
        this.execution = Objects.requireNonNull(execution, "Execution cannot be null");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null");
        this.registry = Objects.requireNonNull(registry, "Registry cannot be null");
        this.maxPayloadChars = positive(maxPayloadChars, "maxPayloadChars");
        this.batchSize = positive(batchSize, "batchSize");
        this.readBlockMs = positive(readBlockMs, "readBlockMs");
        this.reclaimIdleMs = positive(reclaimIdleMs, "reclaimIdleMs");
        this.maxAttempts = positive(maxAttempts, "maxAttempts");
        this.retentionMs = positive(retentionMs, "retentionMs");
        this.retentionMaxEntries = positive(retentionMaxEntries, "retentionMaxEntries");
        this.deduplicationTtlSeconds = positive(deduplicationTtlSeconds, "deduplicationTtlSeconds");
        this.deadLetterMaxEntries = positive(deadLetterMaxEntries, "deadLetterMaxEntries");
    }

    @Override
    public <T extends EventMessage> CompletableFuture<PublishedDurableEvent> publish(String stream, DurableEvent<T> event) {
        String name = name(stream, "stream");
        Objects.requireNonNull(event, "event cannot be null");
        if (shuttingDown.get()) {
            return CompletableFuture.failedFuture(StructuredFailures.closed(execution, "redis.streams.publish"));
        }
        String payload;
        try {
            payload = registry.toJson(event.payload());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(StructuredFailures.serialization(failure, execution, "redis.streams.serialize"));
        }
        if (payload.length() > maxPayloadChars) {
            return CompletableFuture.failedFuture(StructuredFailures.serialization(
                    new IllegalArgumentException("Serialized durable event exceeds configured size limit."),
                    execution, "redis.streams.serialize"));
        }
        String type = name(event.payload().getType(), "event type");
        return AsyncTaskSupport.supplyAsync(execution, "redis.streams.publish", () -> {
            try (Jedis jedis = pool().getResource()) {
                @SuppressWarnings("unchecked")
                List<Object> result = (List<Object>) jedis.eval(PUBLISH_SCRIPT,
                        List.of(name, dedupeKey(name)), List.of(event.eventId(), event.processingKey(), type, payload,
                                Long.toString(deduplicationTtlSeconds)));
                String entryId = String.valueOf(result.get(0));
                boolean created = "1".equals(String.valueOf(result.get(1)));
                return new PublishedDurableEvent(event.eventId(), entryId, created);
            }
        });
    }

    @Override
    public <T extends EventMessage> DurableSubscription consume(
            String stream, String group, String consumer, Class<T> type, Consumer<DurableDelivery<T>> handler
    ) {
        String streamName = name(stream, "stream");
        String groupName = name(group, "group");
        String consumerName = name(consumer, "consumer");
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(handler, "handler cannot be null");
        if (shuttingDown.get()) {
            throw StructuredFailures.closed(execution, "redis.streams.consume");
        }
        String id = streamName + ":" + groupName + ":" + consumerName;
        if (!execution.tryAcquireSubscription()) {
            throw DataProviderExceptionMapper.translate(new ExecutionRejectedException(
                    ExecutionRejectedException.Reason.SUBSCRIPTION_LIMIT, "Durable consumer limit reached."),
                    execution, "redis.streams.consume");
        }
        ConsumerLoop<T> loop = new ConsumerLoop<>(id, streamName, groupName, consumerName, type, handler);
        if (consumers.putIfAbsent(id, loop) != null) {
            execution.releaseSubscription();
            throw new IllegalStateException("A durable consumer already exists for " + id);
        }
        loop.start();
        return loop;
    }

    @Override
    public List<DurableSubscriptionSnapshot> subscriptions() {
        return consumers.values().stream().map(ConsumerLoop::snapshot).toList();
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<?>[] closes = consumers.values().stream().map(ConsumerLoop::closeAsync)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(closes).whenComplete((unused, failure) -> consumers.clear());
    }

    private JedisPool pool() {
        JedisPool pool = poolSupplier.get();
        if (pool == null || pool.isClosed()) {
            throw new IllegalStateException("Redis durable messaging pool is temporarily unavailable.");
        }
        return pool;
    }

    private static int positive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static long positive(long value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static String name(String value, String label) {
        if (value == null || !NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must match " + NAME_PATTERN.pattern());
        }
        return value;
    }

    private static String dedupeKey(String stream) { return stream + ":durable:published"; }
    private static String retryKey(String stream, String group) { return stream + ":durable:retries:" + group; }
    private static String deadLetterKey(String stream, String group) { return stream + ":durable:dead:" + group; }

    private final class ConsumerLoop<T extends EventMessage> implements DurableSubscription {
        private final String id;
        private final String stream;
        private final String group;
        private final String consumer;
        private final Class<T> type;
        private final Consumer<DurableDelivery<T>> handler;
        private final AtomicBoolean closing = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final AtomicReference<String> lastFailure = new AtomicReference<>();
        private final AtomicLong delivered = new AtomicLong();
        private final AtomicLong acknowledged = new AtomicLong();
        private final AtomicLong reclaimed = new AtomicLong();
        private final AtomicLong deadLettered = new AtomicLong();
        private volatile long pending;
        private volatile long lag;

        private ConsumerLoop(String id, String stream, String group, String consumer, Class<T> type,
                             Consumer<DurableDelivery<T>> handler) {
            this.id = id;
            this.stream = stream;
            this.group = group;
            this.consumer = consumer;
            this.type = type;
            this.handler = handler;
        }

        private void start() {
            Thread worker = new Thread(this::run, "redis-stream-" + id.substring(0, Math.min(64, id.length())));
            worker.setDaemon(true);
            worker.start();
        }

        private void run() {
            try {
                ensureGroup();
                StreamEntryID cursor = new StreamEntryID(0, 0);
                while (!closing.get() && !shuttingDown.get()) {
                    try (Jedis jedis = pool().getResource()) {
                        Map.Entry<StreamEntryID, List<StreamEntry>> claims = jedis.xautoclaim(stream, group, consumer,
                                reclaimIdleMs, cursor, XAutoClaimParams.xAutoClaimParams().count(batchSize));
                        cursor = claims.getKey();
                        List<StreamEntry> claimed = claims.getValue();
                        if (!claimed.isEmpty()) {
                            reclaimed.addAndGet(claimed.size());
                            deliver(jedis, claimed);
                        }
                        Map<String, StreamEntryID> streams = Map.of(stream, StreamEntryID.XREADGROUP_UNDELIVERED_ENTRY);
                        List<Map.Entry<String, List<StreamEntry>>> entries = jedis.xreadGroup(group, consumer,
                                XReadGroupParams.xReadGroupParams().count(batchSize).block(readBlockMs), streams);
                        if (entries != null) {
                            for (Map.Entry<String, List<StreamEntry>> entry : entries) deliver(jedis, entry.getValue());
                        }
                        refreshDiagnostics(jedis);
                    } catch (Exception failure) {
                        if (!closing.get() && !shuttingDown.get()) {
                            lastFailure.set(compact(failure));
                            logger.warn("[RedisStreams] Durable consumer " + id + " will retry: " + compact(failure));
                            sleepQuietly(Math.min(readBlockMs, 1_000));
                        }
                    }
                }
                completion.complete(null);
            } catch (Throwable failure) {
                lastFailure.set(compact(failure));
                completion.completeExceptionally(failure);
            } finally {
                consumers.remove(id, this);
                if (released.compareAndSet(false, true)) execution.releaseSubscription();
            }
        }

        private void ensureGroup() {
            try (Jedis jedis = pool().getResource()) {
                try {
                    jedis.xgroupCreate(stream, group, new StreamEntryID(0, 0), true);
                } catch (JedisDataException alreadyExists) {
                    if (alreadyExists.getMessage() == null || !alreadyExists.getMessage().contains("BUSYGROUP")) throw alreadyExists;
                }
            }
        }

        private void deliver(Jedis jedis, List<StreamEntry> entries) {
            for (StreamEntry entry : entries) {
                if (closing.get() || shuttingDown.get()) return;
                delivered.incrementAndGet();
                Map<String, String> fields = entry.getFields();
                String entryId = entry.getID().toString();
                int attempt = Math.toIntExact(jedis.hincrBy(retryKey(stream, group), entryId, 1));
                if (attempt > maxAttempts) {
                    deadLetter(jedis, entry, "Retry policy exhausted after " + maxAttempts + " attempts");
                    continue;
                }
                try {
                    String eventId = required(fields, "event_id");
                    String processingKey = required(fields, "processing_key");
                    T payload = registry.fromJson(required(fields, "payload"), type);
                    if (payload == null || !required(fields, "type").equals(payload.getType())) {
                        throw new IllegalArgumentException("Payload type does not match the durable event envelope");
                    }
                    handler.accept(new Delivery(entry.getID(), new DurableEvent<>(eventId, processingKey, payload), attempt));
                } catch (Exception failure) {
                    lastFailure.set(compact(failure));
                    if (failure instanceof IllegalArgumentException || failure instanceof com.google.gson.JsonSyntaxException) {
                        deadLetter(jedis, entry, "Unprocessable event: " + compact(failure));
                    } else {
                        logger.warn("[RedisStreams] Durable event " + entryId + " failed on " + id
                                + " and remains pending: " + compact(failure));
                    }
                }
            }
        }

        private void deadLetter(Jedis jedis, StreamEntry entry, String failure) {
            Map<String, String> fields = entry.getFields();
            jedis.eval(DEAD_LETTER_SCRIPT, List.of(stream, retryKey(stream, group), deadLetterKey(stream, group)),
                    List.of(group, entry.getID().toString(), fields.getOrDefault("event_id", "unknown"),
                            fields.getOrDefault("processing_key", "unknown"), fields.getOrDefault("type", "unknown"),
                            fields.getOrDefault("payload", ""), failure, Long.toString(deadLetterMaxEntries)));
            deadLettered.incrementAndGet();
        }

        private void refreshDiagnostics(Jedis jedis) {
            for (StreamGroupInfo info : jedis.xinfoGroups(stream)) {
                if (group.equals(info.getName())) {
                    pending = info.getPending();
                    Object reportedLag = info.getGroupInfo().get("lag");
                    lag = reportedLag instanceof Number number ? number.longValue() : -1L;
                    return;
                }
            }
        }

        @Override public String id() { return id; }

        @Override public DurableSubscriptionSnapshot snapshot() {
            return new DurableSubscriptionSnapshot(id, stream, group, consumer, !closing.get() && !completion.isDone(),
                    pending, lag, delivered.get(), acknowledged.get(), reclaimed.get(), deadLettered.get(), lastFailure.get());
        }

        @Override public CompletableFuture<Void> closeAsync() {
            // Do not interrupt a user handler: it may be between committing an idempotent effect and ACK.
            // The bounded XREAD block observes this flag promptly, and any unacknowledged entry stays reclaimable.
            closing.set(true);
            return completion;
        }

        @Override public CompletableFuture<Void> completion() { return completion; }

        private final class Delivery implements DurableDelivery<T> {
            private final StreamEntryID entryId;
            private final DurableEvent<T> event;
            private final int attempt;
            private final AtomicReference<CompletableFuture<Void>> acknowledgement = new AtomicReference<>();

            private Delivery(StreamEntryID entryId, DurableEvent<T> event, int attempt) {
                this.entryId = entryId;
                this.event = event;
                this.attempt = attempt;
            }

            @Override public String stream() { return stream; }
            @Override public String group() { return group; }
            @Override public String consumer() { return consumer; }
            @Override public String streamEntryId() { return entryId.toString(); }
            @Override public DurableEvent<T> event() { return event; }
            @Override public int attempt() { return attempt; }

            @Override public synchronized CompletableFuture<Void> acknowledge() {
                CompletableFuture<Void> current = acknowledgement.get();
                if (current != null) return current;
                CompletableFuture<Void> created = AsyncTaskSupport.runAsync(execution, "redis.streams.ack", () -> {
                    try (Jedis jedis = pool().getResource()) {
                        Object result = jedis.eval(ACK_SCRIPT, List.of(stream, retryKey(stream, group)),
                                List.of(group, entryId.toString()));
                        if (((Number) result).longValue() > 0L) {
                            acknowledged.incrementAndGet();
                            trimAfterAcknowledgement(stream);
                        }
                    }
                });
                // Avoid duplicate acknowledgements even when callers race from multiple callback threads.
                if (acknowledgement.compareAndSet(null, created)) return created;
                return acknowledgement.get();
            }
        }
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Durable event has no " + key);
        return value;
    }

    private void trimAfterAcknowledgement(String stream) {
        try (Jedis jedis = pool().getResource()) {
            StreamEntryID cutoff = new StreamEntryID(Math.max(0L, System.currentTimeMillis() - retentionMs), 0);
            List<StreamEntry> retained = jedis.xrevrange(stream, StreamEntryID.MAXIMUM_ID, StreamEntryID.MINIMUM_ID,
                    Math.toIntExact(Math.min(retentionMaxEntries, Integer.MAX_VALUE)));
            if (retained.size() == retentionMaxEntries) cutoff = older(cutoff, retained.get(retained.size() - 1).getID());
            for (StreamGroupInfo group : jedis.xinfoGroups(stream)) {
                StreamPendingSummary pending = jedis.xpending(stream, group.getName());
                if (pending.getMinId() != null) cutoff = older(cutoff, pending.getMinId());
            }
            jedis.xtrim(stream, XTrimParams.xTrimParams().minId(cutoff.toString()).exactTrimming());
        } catch (Exception failure) {
            logger.warn("[RedisStreams] Safe retention trim failed for " + stream + ": " + compact(failure));
        }
    }

    private static StreamEntryID older(StreamEntryID first, StreamEntryID second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static String compact(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
