package nl.hauntedmc.dataprovider.core.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.core.concurrent.AsyncTaskSupport;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionRejectedException;
import nl.hauntedmc.dataprovider.core.exception.DataProviderExceptionMapper;
import nl.hauntedmc.dataprovider.core.exception.StructuredFailures;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionState;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
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
    private static final String PUBLISH_SCRIPT = "local saved=redis.call('GET',KEYS[2]);"
            + " if saved then local separator=string.find(saved,'|',1,true);"
            + " if not separator or string.sub(saved,separator+1) ~= ARGV[5] then"
            + " return redis.error_reply('DURABLE_EVENT_ID_CONFLICT') end;"
            + " return {string.sub(saved,1,separator-1),'0'} end;"
            + " local id=redis.call('XADD',KEYS[1],'*','event_id',ARGV[1],'processing_key',ARGV[2],"
            + "'type',ARGV[3],'payload',ARGV[4]); redis.call('SET',KEYS[2],id..'|'..ARGV[5],'EX',ARGV[6]);"
            + " return {id,'1'};";
    private static final String ACK_SCRIPT = "local n=redis.call('XACK',KEYS[1],ARGV[1],ARGV[2]);"
            + " if n>0 then redis.call('HDEL',KEYS[2],ARGV[2]) end; return n;";
    private static final String DEAD_LETTER_SCRIPT = "local attempt=redis.call('HGET',KEYS[2],ARGV[2]) or ARGV[7];"
            + " redis.call('XADD',KEYS[3],'MAXLEN',ARGV[8],'*','source_stream',KEYS[1],"
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
    private final long retentionTrimIntervalMs;
    private final long reconnectInitialBackoffMs;
    private final long reconnectMaxBackoffMs;
    private final double reconnectJitter;
    private final int reconnectMaxAttempts;
    private final Map<String, ConsumerLoop<?>> consumers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> nextTrimAtMillis = new ConcurrentHashMap<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Void>> shutdownFuture = new AtomicReference<>();

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
            long deadLetterMaxEntries,
            long retentionTrimIntervalMs,
            long reconnectInitialBackoffMs,
            long reconnectMaxBackoffMs,
            double reconnectJitter,
            int reconnectMaxAttempts
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
        this.retentionTrimIntervalMs = positive(retentionTrimIntervalMs, "retentionTrimIntervalMs");
        this.reconnectInitialBackoffMs = nonNegative(reconnectInitialBackoffMs, "reconnectInitialBackoffMs");
        this.reconnectMaxBackoffMs = atLeast(reconnectMaxBackoffMs, this.reconnectInitialBackoffMs,
                "reconnectMaxBackoffMs");
        if (reconnectJitter < 0.0D || reconnectJitter > 1.0D || !Double.isFinite(reconnectJitter)) {
            throw new IllegalArgumentException("reconnectJitter must be between zero and one");
        }
        this.reconnectJitter = reconnectJitter;
        this.reconnectMaxAttempts = nonNegative(reconnectMaxAttempts, "reconnectMaxAttempts");
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
        String fingerprint = fingerprint(event.processingKey(), type, payload);
        return AsyncTaskSupport.supplyAsync(execution, "redis.streams.publish", () -> {
            try (Jedis jedis = pool().getResource()) {
                @SuppressWarnings("unchecked")
                List<Object> result = (List<Object>) jedis.eval(PUBLISH_SCRIPT,
                        List.of(name, dedupeKey(name, event.eventId())), List.of(event.eventId(), event.processingKey(), type,
                                payload, fingerprint, Long.toString(deduplicationTtlSeconds)));
                String entryId = String.valueOf(result.get(0));
                boolean created = "1".equals(String.valueOf(result.get(1)));
                return new PublishedDurableEvent(event.eventId(), entryId, created);
            }
        });
    }

    @Override
    public <T extends EventMessage> DurableSubscription consume(
            String stream, String group, String consumer, String messageType, Class<T> type,
            Consumer<DurableDelivery<T>> handler
    ) {
        String streamName = name(stream, "stream");
        String groupName = name(group, "group");
        String consumerName = name(consumer, "consumer");
        String expectedMessageType = MessageRegistry.validateType(messageType);
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
        ConsumerLoop<T> loop = new ConsumerLoop<>(
                id, streamName, groupName, consumerName, expectedMessageType, type, handler
        );
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
        CompletableFuture<Void> created;
        synchronized (shutdownFuture) {
            CompletableFuture<Void> existing = shutdownFuture.get();
            if (existing != null) {
                return existing;
            }
            shuttingDown.set(true);
            created = new CompletableFuture<>();
            shutdownFuture.set(created);
        }
        try {
            CompletableFuture<?>[] closes = consumers.values().stream().map(ConsumerLoop::closeAsync)
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(closes).whenComplete((unused, failure) -> {
                consumers.clear();
                if (failure == null) {
                    created.complete(null);
                } else {
                    created.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            consumers.clear();
            created.completeExceptionally(failure);
        }
        return created;
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

    private static long nonNegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " cannot be negative");
        return value;
    }

    private static long atLeast(long value, long minimum, String name) {
        if (value < minimum) throw new IllegalArgumentException(name + " must be at least " + minimum);
        return value;
    }

    private static int nonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " cannot be negative");
        return value;
    }

    private static String name(String value, String label) {
        if (value == null || !NAME_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " must match " + NAME_PATTERN.pattern());
        }
        return value;
    }

    private static String dedupeKey(String stream, String eventId) { return stream + ":durable:published:" + eventId; }
    private static String retryKey(String stream, String group) { return stream + ":durable:retries:" + group; }
    private static String deadLetterKey(String stream, String group) { return stream + ":durable:dead:" + group; }

    private static String fingerprint(String processingKey, String type, String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(processingKey.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(type.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", unavailable);
        }
    }

    private final class ConsumerLoop<T extends EventMessage> implements DurableSubscription {
        private final String id;
        private final String stream;
        private final String group;
        private final String consumer;
        private final String messageType;
        private final Class<T> type;
        private final Consumer<DurableDelivery<T>> handler;
        private final AtomicBoolean closing = new AtomicBoolean();
        private final AtomicBoolean released = new AtomicBoolean();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final AtomicReference<SubscriptionState> state = new AtomicReference<>(SubscriptionState.CONNECTING);
        private final AtomicReference<Throwable> terminalFailure = new AtomicReference<>();
        private final AtomicReference<String> lastFailure = new AtomicReference<>();
        private final AtomicReference<Instant> lastFailureAt = new AtomicReference<>();
        private final AtomicLong delivered = new AtomicLong();
        private final AtomicLong acknowledged = new AtomicLong();
        private final AtomicLong reclaimed = new AtomicLong();
        private final AtomicLong deadLettered = new AtomicLong();
        private final AtomicLong reconnectCount = new AtomicLong();
        private final AtomicLong downtimeStartedNanos = new AtomicLong(-1L);
        private final AtomicLong totalDowntimeNanos = new AtomicLong();
        private final AtomicLong nextOutageLogNanos = new AtomicLong();
        private final Object backoffMonitor = new Object();
        private boolean groupReady;
        private volatile long pending;
        private volatile long lag;

        private ConsumerLoop(
                String id,
                String stream,
                String group,
                String consumer,
                String messageType,
                Class<T> type,
                Consumer<DurableDelivery<T>> handler
        ) {
            this.id = id;
            this.stream = stream;
            this.group = group;
            this.consumer = consumer;
            this.messageType = messageType;
            this.type = type;
            this.handler = handler;
        }

        private void start() {
            Thread worker = new Thread(this::run, "redis-stream-" + id.substring(0, Math.min(64, id.length())));
            worker.setDaemon(true);
            worker.start();
        }

        private void run() {
            int consecutiveFailures = 0;
            boolean recoveryPending = false;
            try {
                StreamEntryID cursor = new StreamEntryID(0, 0);
                while (!closing.get() && !shuttingDown.get()) {
                    if (recoveryPending) {
                        reconnectCount.incrementAndGet();
                        setRecovering();
                        if (!awaitBackoff(consecutiveFailures)) {
                            break;
                        }
                        recoveryPending = false;
                    }
                    if (closing.get() || shuttingDown.get()) {
                        break;
                    }
                    try (Jedis jedis = pool().getResource()) {
                        ensureGroup(jedis);
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
                        setActive();
                        consecutiveFailures = 0;
                    } catch (Exception failure) {
                        groupReady = false;
                        if (!closing.get() && !shuttingDown.get()) {
                            consecutiveFailures = state.get() == SubscriptionState.ACTIVE
                                    ? 1 : consecutiveFailures + 1;
                            boolean terminal = isTerminalFailure(failure);
                            recordFailure(failure, !terminal);
                            beginDowntime();
                            if (terminal) {
                                failTerminal(failure);
                                return;
                            }
                            recoveryPending = true;
                        }
                    }
                }
            } catch (Throwable failure) {
                recordFailure(failure);
                failTerminal(failure);
            } finally {
                consumers.remove(id, this);
                if (released.compareAndSet(false, true)) execution.releaseSubscription();
                if (state.get() == SubscriptionState.FAILED) {
                    completion.completeExceptionally(terminalFailure.get());
                } else {
                    finishClosed();
                }
            }
        }

        private boolean awaitBackoff(int consecutiveFailures) {
            long delay = reconnectDelayMillis(Math.max(1, consecutiveFailures));
            if (delay == 0L) {
                return !closing.get() && !shuttingDown.get();
            }
            try {
                synchronized (backoffMonitor) {
                    if (!closing.get() && !shuttingDown.get()) {
                        backoffMonitor.wait(delay);
                    }
                }
                return !closing.get() && !shuttingDown.get();
            } catch (InterruptedException interrupted) {
                if (!closing.get() && !shuttingDown.get()) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
        }

        private long reconnectDelayMillis(int consecutiveFailures) {
            int shift = Math.min(30, Math.max(0, consecutiveFailures - 1));
            long exponential;
            try {
                exponential = Math.multiplyExact(reconnectInitialBackoffMs, 1L << shift);
            } catch (ArithmeticException overflow) {
                exponential = reconnectMaxBackoffMs;
            }
            long capped = Math.min(reconnectMaxBackoffMs, exponential);
            if (capped == 0L || reconnectJitter == 0.0D) {
                return capped;
            }
            double factor = ThreadLocalRandom.current().nextDouble(
                    Math.max(0.0D, 1.0D - reconnectJitter), 1.0D + reconnectJitter);
            return Math.max(0L, Math.min(reconnectMaxBackoffMs, Math.round(capped * factor)));
        }

        private boolean isTerminalFailure(Throwable failure) {
            return RedisRetryClassifier.isTerminal(failure)
                    || reconnectMaxAttempts > 0 && reconnectCount.get() >= reconnectMaxAttempts;
        }

        private void recordFailure(Throwable failure) {
            recordFailure(failure, false);
        }

        private void recordFailure(Throwable failure, boolean logOutage) {
            lastFailure.set(compact(failure));
            lastFailureAt.set(Instant.now());
            if (!logOutage) {
                return;
            }
            long now = System.nanoTime();
            long next = nextOutageLogNanos.get();
            if (now < next || !nextOutageLogNanos.compareAndSet(next, now + Duration.ofSeconds(30).toNanos())) {
                return;
            }
            logger.warn("[RedisStreams] Durable consumer " + id + " is unavailable; automatic reconnect is active.",
                    failure);
        }

        private void beginDowntime() {
            downtimeStartedNanos.compareAndSet(-1L, System.nanoTime());
            setRecovering();
        }

        private void endDowntime() {
            long startedAt = downtimeStartedNanos.getAndSet(-1L);
            if (startedAt >= 0L) {
                totalDowntimeNanos.addAndGet(Math.max(0L, System.nanoTime() - startedAt));
            }
        }

        private void setRecovering() {
            state.updateAndGet(current -> switch (current) {
                case CONNECTING, ACTIVE, RECONNECTING -> SubscriptionState.RECONNECTING;
                case CLOSING, CLOSED, FAILED -> current;
            });
        }

        private void setActive() {
            if (!closing.get() && !shuttingDown.get()) {
                long startedAt = downtimeStartedNanos.get();
                endDowntime();
                if (startedAt >= 0L) {
                    logger.info("[RedisStreams] Durable consumer " + id + " recovered after "
                            + Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAt)).toMillis()
                            + "ms and " + reconnectCount.get() + " reconnect attempts.");
                }
                state.updateAndGet(current -> switch (current) {
                    case CONNECTING, RECONNECTING -> SubscriptionState.ACTIVE;
                    case ACTIVE, CLOSING, CLOSED, FAILED -> current;
                });
            }
        }

        private void failTerminal(Throwable failure) {
            if (closing.get() || shuttingDown.get()) {
                return;
            }
            if (terminalFailure.compareAndSet(null, failure)) {
                state.set(SubscriptionState.FAILED);
                logger.error("[RedisStreams] Durable consumer " + id
                        + " reached a terminal failure and will not reconnect.", failure);
            }
        }

        private void finishClosed() {
            if (state.get() != SubscriptionState.FAILED) {
                state.set(SubscriptionState.CLOSED);
                endDowntime();
                completion.complete(null);
            }
        }

        private void ensureGroup(Jedis jedis) {
            if (groupReady) {
                return;
            }
            try {
                jedis.xgroupCreate(stream, group, new StreamEntryID(0, 0), true);
            } catch (JedisDataException alreadyExists) {
                if (alreadyExists.getMessage() == null || !alreadyExists.getMessage().contains("BUSYGROUP")) throw alreadyExists;
            }
            groupReady = true;
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
                DurableEvent<T> event;
                try {
                    String eventId = required(fields, "event_id");
                    String processingKey = required(fields, "processing_key");
                    String envelopeType = required(fields, "type");
                    if (!messageType.equals(envelopeType)) {
                        throw new IllegalArgumentException("Envelope type does not match the consumer contract");
                    }
                    T payload = registry.fromJson(required(fields, "payload"), type, messageType);
                    if (!envelopeType.equals(payload.getType())) {
                        throw new IllegalArgumentException("Payload type does not match the durable event envelope");
                    }
                    event = new DurableEvent<>(eventId, processingKey, payload);
                } catch (Exception failure) {
                    recordFailure(failure);
                    deadLetter(jedis, entry, "Unprocessable event: " + compact(failure));
                    continue;
                }
                try {
                    AsyncTaskSupport.runAsync(execution, "redis.streams.handle", () ->
                            handler.accept(new Delivery(entry.getID(), event, attempt))).join();
                } catch (Exception failure) {
                    recordFailure(failure);
                    logger.warn("[RedisStreams] Durable event " + entryId + " failed on " + id
                            + " and remains pending: " + compact(failure));
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

        @Override public SubscriptionState state() { return state.get(); }

        @Override public DurableSubscriptionSnapshot snapshot() {
            long currentDowntime = currentDowntimeNanos();
            SubscriptionState currentState = state.get();
            return new DurableSubscriptionSnapshot(id, stream, group, consumer, currentState == SubscriptionState.ACTIVE,
                    pending, lag, delivered.get(), acknowledged.get(), reclaimed.get(), deadLettered.get(), lastFailure.get(),
                    currentState, reconnectCount.get(), lastFailureAt.get(), Duration.ofNanos(currentDowntime),
                    Duration.ofNanos(totalDowntimeNanos.get() + currentDowntime));
        }

        private long currentDowntimeNanos() {
            long startedAt = downtimeStartedNanos.get();
            return startedAt < 0L ? 0L : Math.max(0L, System.nanoTime() - startedAt);
        }

        @Override public CompletableFuture<Void> closeAsync() {
            // Do not interrupt a user handler: it may be between committing an idempotent effect and ACK.
            // The bounded XREAD block observes this flag promptly, and any unacknowledged entry stays reclaimable.
            if (closing.compareAndSet(false, true)) {
                state.updateAndGet(current -> current == SubscriptionState.FAILED ? current : SubscriptionState.CLOSING);
                synchronized (backoffMonitor) {
                    backoffMonitor.notifyAll();
                }
            }
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
                if (current != null && !current.isCompletedExceptionally() && !current.isCancelled()) return current;
                CompletableFuture<Void> created = new CompletableFuture<>();
                try {
                    try (Jedis jedis = pool().getResource()) {
                        Object result = jedis.eval(ACK_SCRIPT, List.of(stream, retryKey(stream, group)),
                                List.of(group, entryId.toString()));
                        if (((Number) result).longValue() > 0L) {
                            acknowledged.incrementAndGet();
                            trimAfterAcknowledgement(stream);
                        }
                    }
                    created.complete(null);
                } catch (Throwable failure) {
                    created.completeExceptionally(failure);
                }
                acknowledgement.set(created);
                return created;
            }
        }
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Durable event has no " + key);
        return value;
    }

    private void trimAfterAcknowledgement(String stream) {
        long now = System.currentTimeMillis();
        AtomicLong nextAt = nextTrimAtMillis.computeIfAbsent(stream, ignored -> new AtomicLong());
        long previouslyScheduled = nextAt.get();
        if (now < previouslyScheduled || !nextAt.compareAndSet(previouslyScheduled, now + retentionTrimIntervalMs)) {
            return;
        }
        try (Jedis jedis = pool().getResource()) {
            StreamEntryID cutoff = new StreamEntryID(Math.max(0L, redisTimeMillis(jedis) - retentionMs), 0);
            List<StreamEntry> retained = jedis.xrevrange(stream, StreamEntryID.MAXIMUM_ID, StreamEntryID.MINIMUM_ID,
                    Math.toIntExact(Math.min(retentionMaxEntries, Integer.MAX_VALUE)));
            if (retained.size() == retentionMaxEntries) cutoff = newer(cutoff, retained.get(retained.size() - 1).getID());
            for (StreamGroupInfo group : jedis.xinfoGroups(stream)) {
                if (group.getLastDeliveredId() != null) cutoff = older(cutoff, group.getLastDeliveredId());
                StreamPendingSummary pending = jedis.xpending(stream, group.getName());
                if (pending.getMinId() != null) cutoff = older(cutoff, pending.getMinId());
            }
            jedis.xtrim(stream, XTrimParams.xTrimParams().minId(cutoff.toString()).exactTrimming());
        } catch (Exception failure) {
            logger.warn("[RedisStreams] Safe retention trim failed for " + stream + ": " + compact(failure));
        }
    }

    private static long redisTimeMillis(Jedis jedis) {
        List<String> time = jedis.time();
        return Math.addExact(Math.multiplyExact(Long.parseLong(time.getFirst()), 1_000L),
                Long.parseLong(time.get(1)) / 1_000L);
    }

    private static StreamEntryID older(StreamEntryID first, StreamEntryID second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static StreamEntryID newer(StreamEntryID first, StreamEntryID second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    private static String compact(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
