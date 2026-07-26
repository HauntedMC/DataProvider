package nl.hauntedmc.dataprovider.core.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.core.ManagedDatabaseProvider;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.database.security.TlsSupport;
import nl.hauntedmc.dataprovider.core.logging.RateLimitedLogger;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;
import nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionSnapshot;
import nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionState;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableMessagingDataAccess;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import javax.net.ssl.SSLContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Redis messaging provider backed by the shared messaging execution lane. */
@SuppressWarnings("deprecation")
public final class RedisMessagingDatabase implements MessagingDatabaseProvider, ManagedDatabaseProvider {

    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._:\\-\\[\\]]+");

    private final CommentedConfigurationNode config;
    private final LoggerAdapter logger;
    private final MessageRegistry messageRegistry;
    private final ExecutionHandle execution;
    private final RateLimitedLogger outageLogger = new RateLimitedLogger(Duration.ofSeconds(30));
    private final ConcurrentMap<Object, MessagingDataAccess> scopedAccess = new ConcurrentHashMap<>();
    private final ConcurrentMap<Object, DurableMessagingDataAccess> scopedDurableAccess = new ConcurrentHashMap<>();
    private final AtomicLong accessSequence = new AtomicLong();
    private volatile JedisPool pool;
    private volatile MessagingDataAccess bus;
    private volatile DurableMessagingDataAccess durableBus;
    private volatile boolean connected;
    private volatile Throwable lifecycleFailure;
    private volatile int maxSubscriptions;
    private volatile int maxPayloadChars;
    private volatile int maxQueuedMessagesPerHandler;
    private volatile int handlerBatchSize;
    private volatile int commandPoolSize;
    private volatile long reconnectInitialBackoffMs;
    private volatile long reconnectMaxBackoffMs;
    private volatile double reconnectJitter;
    private volatile int reconnectMaxAttempts;
    private volatile int durableBatchSize;
    private volatile int durableReadBlockMs;
    private volatile long durableReclaimIdleMs;
    private volatile int durableMaxAttempts;
    private volatile long durableRetentionMs;
    private volatile long durableRetentionMaxEntries;
    private volatile long durableDeduplicationTtlSeconds;
    private volatile long durableDeadLetterMaxEntries;
    private volatile long durableRetentionTrimIntervalMs;

    public RedisMessagingDatabase(CommentedConfigurationNode config, LoggerAdapter logger) {
        this(config, logger, ExecutionHandle.direct());
    }

    public RedisMessagingDatabase(
            CommentedConfigurationNode config,
            LoggerAdapter logger,
            ExecutionHandle execution
    ) {
        this.config = Objects.requireNonNull(config, "Config cannot be null.");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
        this.messageRegistry = new MessageRegistry(logger);
        this.execution = Objects.requireNonNull(execution, "Execution handle cannot be null.");
    }

    @Override
    public synchronized void connect() {
        if (connected) {
            return;
        }

        String host = requireHost(config.node("host").getString("localhost"));
        int port = requireInRange(config.node("port").getInt(6379), 1, 65_535, "port");
        int database = requireInRange(config.node("database").getInt(0), 0, 65_535, "database");
        String user = config.node("user").getString("");
        String password = config.node("password").getString("");
        int commandPoolSize = requireInRange(config.node("pool", "connections").getInt(4),
                1, 256, "pool.connections");
        int maxSubscriptions = requireInRange(config.node("pool", "max_subscriptions").getInt(64),
                1, 10_000, "pool.max_subscriptions");
        int maxPayloadChars = requireInRange(config.node("security", "max_payload_chars").getInt(32_768),
                256, 1_000_000, "security.max_payload_chars");
        int maxQueuedMessagesPerHandler = requireInRange(
                config.node("security", "max_queued_messages_per_handler").getInt(1_024),
                1, 1_000_000, "security.max_queued_messages_per_handler");
        int handlerBatchSize = requireInRange(config.node("pool", "handler_batch_size").getInt(64),
                1, 10_000, "pool.handler_batch_size");
        int maxIdleConnections = requireInRange(
                config.node("pool", "max_idle").getInt(commandPoolSize),
                0, commandPoolSize, "pool.max_idle");
        int minIdleConnections = requireInRange(
                config.node("pool", "min_idle").getInt(Math.min(2, commandPoolSize)),
                0, maxIdleConnections, "pool.min_idle");
        int connectionTimeoutMs = requireInRange(config.node("connection_timeout_ms").getInt(2_000),
                250, 300_000, "connection_timeout_ms");
        int socketTimeoutMs = requireInRange(config.node("socket_timeout_ms").getInt(2_000),
                250, 300_000, "socket_timeout_ms");
        long reconnectInitialBackoffMs = requireInRange(
                config.node("reconnect", "initial_backoff_ms").getLong(250L),
                0L, 300_000L, "reconnect.initial_backoff_ms");
        long reconnectMaxBackoffMs = requireInRange(
                config.node("reconnect", "max_backoff_ms").getLong(10_000L),
                reconnectInitialBackoffMs, 300_000L, "reconnect.max_backoff_ms");
        double reconnectJitter = requireDoubleInRange(
                config.node("reconnect", "jitter").getDouble(0.20D),
                0.0D, 1.0D, "reconnect.jitter");
        int reconnectMaxAttempts = requireInRange(
                config.node("reconnect", "max_attempts").getInt(0),
                0, 1_000_000, "reconnect.max_attempts");
        int durableBatchSize = requireInRange(config.node("durable", "batch_size").getInt(32),
                1, 10_000, "durable.batch_size");
        int durableReadBlockMs = requireInRange(config.node("durable", "read_block_ms").getInt(500),
                50, 30_000, "durable.read_block_ms");
        long durableReclaimIdleMs = requireInRange(config.node("durable", "reclaim_idle_ms").getLong(30_000L),
                1_000L, 3_600_000L, "durable.reclaim_idle_ms");
        int durableMaxAttempts = requireInRange(config.node("durable", "max_attempts").getInt(8),
                1, 1_000_000, "durable.max_attempts");
        long durableRetentionMs = requireInRange(config.node("durable", "retention_ms").getLong(604_800_000L),
                60_000L, 31_536_000_000L, "durable.retention_ms");
        long durableRetentionMaxEntries = requireInRange(config.node("durable", "retention_max_entries").getLong(1_000_000L),
                1L, Integer.MAX_VALUE, "durable.retention_max_entries");
        long durableDeduplicationTtlSeconds = requireInRange(
                config.node("durable", "deduplication_ttl_seconds").getLong(604_800L),
                60L, 31_536_000L, "durable.deduplication_ttl_seconds");
        long durableDeadLetterMaxEntries = requireInRange(
                config.node("durable", "dead_letter_max_entries").getLong(100_000L),
                1L, Integer.MAX_VALUE, "durable.dead_letter_max_entries");
        long durableRetentionTrimIntervalMs = requireInRange(
                config.node("durable", "retention_trim_interval_ms").getLong(30_000L),
                1_000L, 3_600_000L, "durable.retention_trim_interval_ms");
        long minimumSocketTimeoutMs = Math.addExact((long) durableReadBlockMs, 250L);
        if (socketTimeoutMs < minimumSocketTimeoutMs) {
            throw new IllegalArgumentException("Redis messaging config 'socket_timeout_ms' must be at least "
                    + minimumSocketTimeoutMs + "ms to exceed durable.read_block_ms.");
        }
        if (durableDeduplicationTtlSeconds < Math.ceilDiv(durableRetentionMs, 1_000L)) {
            throw new IllegalArgumentException("Redis messaging config 'durable.deduplication_ttl_seconds' must be at least "
                    + "durable.retention_ms rounded up to seconds.");
        }
        boolean tlsEnabled = config.node("tls", "enabled").getBoolean(false);
        boolean verifyHostname = config.node("tls", "verify_hostname").getBoolean(true);
        boolean trustAllCertificates = config.node("tls", "trust_all_certificates").getBoolean(false);
        String trustStorePath = config.node("tls", "trust_store_path").getString("");
        String trustStorePassword = config.node("tls", "trust_store_password").getString("");
        String trustStoreType = config.node("tls", "trust_store_type").getString("");
        boolean requireSecureTransport = config.node("require_secure_transport").getBoolean(false);

        if (requireSecureTransport && !tlsEnabled) {
            throw new IllegalStateException("Redis messaging require_secure_transport=true but tls.enabled=false");
        }
        if (!tlsEnabled) {
            logger.warn("[RedisMessagingDatabase] Redis messaging is running without TLS.");
        } else if (!verifyHostname || trustAllCertificates) {
            throw new IllegalStateException("Redis messaging hostname and certificate verification cannot be disabled.");
        }
        if (!user.isBlank() && password.isBlank()) {
            logger.warn("[RedisMessagingDatabase] Redis messaging user is configured without a password.");
        }

        JedisPool createdPool = null;
        try {
            int totalPoolCapacity = Math.addExact(commandPoolSize, maxSubscriptions);
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            // Each subscription owns one long-lived connection. Extra capacity keeps command operations isolated.
            poolConfig.setMaxTotal(totalPoolCapacity);
            poolConfig.setMaxIdle(maxIdleConnections);
            poolConfig.setMinIdle(minIdleConnections);
            poolConfig.setTestOnBorrow(config.node("pool", "test_on_borrow").getBoolean(true));
            poolConfig.setTestWhileIdle(config.node("pool", "test_while_idle").getBoolean(true));
            poolConfig.setBlockWhenExhausted(true);

            DefaultJedisClientConfig.Builder clientConfigBuilder = DefaultJedisClientConfig.builder()
                    .user(user.isBlank() ? null : user)
                    .password(password.isBlank() ? null : password)
                    .database(database)
                    .connectionTimeoutMillis(connectionTimeoutMs)
                    .socketTimeoutMillis(socketTimeoutMs)
                    .ssl(tlsEnabled);
            if (tlsEnabled) {
                SSLContext sslContext = TlsSupport.createSslContext(trustStorePath, trustStorePassword, trustStoreType);
                clientConfigBuilder.sslSocketFactory(sslContext.getSocketFactory());
                clientConfigBuilder.hostnameVerifier(TlsSupport.strictHostnameVerifier());
            }

            createdPool = new JedisPool(poolConfig, new HostAndPort(host, port), clientConfigBuilder.build());
            try (Jedis jedis = createdPool.getResource()) {
                if (!"PONG".equalsIgnoreCase(jedis.ping())) {
                    throw new IllegalStateException("Redis messaging ping check failed.");
                }
            }

            pool = createdPool;
            this.commandPoolSize = commandPoolSize;
            this.maxSubscriptions = maxSubscriptions;
            this.maxPayloadChars = maxPayloadChars;
            this.maxQueuedMessagesPerHandler = maxQueuedMessagesPerHandler;
            this.handlerBatchSize = handlerBatchSize;
            this.reconnectInitialBackoffMs = reconnectInitialBackoffMs;
            this.reconnectMaxBackoffMs = reconnectMaxBackoffMs;
            this.reconnectJitter = reconnectJitter;
            this.reconnectMaxAttempts = reconnectMaxAttempts;
            this.durableBatchSize = durableBatchSize;
            this.durableReadBlockMs = durableReadBlockMs;
            this.durableReclaimIdleMs = durableReclaimIdleMs;
            this.durableMaxAttempts = durableMaxAttempts;
            this.durableRetentionMs = durableRetentionMs;
            this.durableRetentionMaxEntries = durableRetentionMaxEntries;
            this.durableDeduplicationTtlSeconds = durableDeduplicationTtlSeconds;
            this.durableDeadLetterMaxEntries = durableDeadLetterMaxEntries;
            this.durableRetentionTrimIntervalMs = durableRetentionTrimIntervalMs;
            bus = newAccess(execution, messageRegistry);
            durableBus = newDurableAccess(execution, messageRegistry);
            connected = true;
            lifecycleFailure = null;
            logger.info(String.format(
                    "[RedisMessagingDatabase] Connected at %s:%d (db=%d, auth=%s, tls=%s, commandCapacity=%d, subscriptionCapacity=%d)",
                    host, port, database, password.isBlank() ? "disabled" : "enabled",
                    tlsEnabled ? "enabled" : "disabled", commandPoolSize, maxSubscriptions));
        } catch (Exception failure) {
            lifecycleFailure = failure;
            connected = false;
            if (createdPool != null && !createdPool.isClosed()) {
                createdPool.close();
            }
            pool = null;
            bus = null;
            durableBus = null;
            outageLogger.error(logger, "[RedisMessagingDatabase] Connection failed. ("
                    + failure.getClass().getSimpleName() + ").");
        }
    }

    @Override
    public synchronized void disconnect() {
        List<CompletableFuture<Void>> shutdowns = new ArrayList<>();
        Set<MessagingDataAccess> ephemeralAccess = new HashSet<>(scopedAccess.values());
        if (bus != null) {
            ephemeralAccess.add(bus);
        }
        ephemeralAccess.forEach(access -> shutdowns.add(access.shutdown()));
        Set<DurableMessagingDataAccess> durableAccess = new HashSet<>(scopedDurableAccess.values());
        if (durableBus != null) {
            durableAccess.add(durableBus);
        }
        durableAccess.forEach(access -> shutdowns.add(access.shutdown()));
        try {
            CompletableFuture.allOf(shutdowns.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
        } catch (Exception failure) {
            logger.warn("[RedisMessagingDatabase] Timed out while shutting down messaging consumers; "
                    + "unacknowledged durable entries remain reclaimable.");
        } finally {
            execution.close();
            if (pool != null && !pool.isClosed()) {
                pool.close();
            }
            pool = null;
            bus = null;
            durableBus = null;
            scopedAccess.clear();
            scopedDurableAccess.clear();
            connected = false;
        }
    }

    @Override
    public boolean isConnected() {
        JedisPool snapshot = pool;
        return connected && snapshot != null && !snapshot.isClosed();
    }

    @Override
    public Throwable lifecycleFailure() {
        return lifecycleFailure;
    }

    @Override
    public boolean probeRemoteHealth() {
        JedisPool snapshot = pool;
        if (!connected || snapshot == null || snapshot.isClosed()) {
            return false;
        }
        try (Jedis jedis = snapshot.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception failure) {
            return false;
        }
    }

    @Override
    public MessagingDataAccess getDataAccess() {
        return bus;
    }

    @Override
    public DurableMessagingDataAccess getDurableDataAccess() {
        return durableBus;
    }

    public int executionCapacity() {
        if (!isConnected() || commandPoolSize < 1) {
            throw new IllegalStateException("[RedisMessagingDatabase] Jedis pool not initialized!");
        }
        return commandPoolSize;
    }

    /** Number of long-lived subscription connections reserved by this physical pool. */
    public int subscriptionCapacity() {
        if (!isConnected() || maxSubscriptions < 1) {
            throw new IllegalStateException("[RedisMessagingDatabase] Jedis pool not initialized!");
        }
        return maxSubscriptions;
    }

    /** Creates or reuses one durable logical provider view for an execution scope. */
    public MessagingDatabaseProvider scoped(ExecutionHandle scopedExecution) {
        if (!isConnected()) {
            throw new IllegalStateException("[RedisMessagingDatabase] Jedis pool not initialized!");
        }
        Object scopeIdentity = scopedExecution.scopeIdentity();
        MessagingDataAccess accessView = scopedAccess.computeIfAbsent(
                scopeIdentity,
                ignored -> newAccess(scopedExecution, new MessageRegistry(logger))
        );
        DurableMessagingDataAccess durableAccessView = scopedDurableAccess.computeIfAbsent(
                scopeIdentity,
                ignored -> newDurableAccess(scopedExecution, new MessageRegistry(logger))
        );
        return new MessagingDatabaseProvider() {
            @Override public boolean isConnected() {
                return RedisMessagingDatabase.this.isConnected() && !scopedExecution.isClosed();
            }
            @Override public MessagingDataAccess getDataAccess() { return accessView; }
            @Override public DurableMessagingDataAccess getDurableDataAccess() { return durableAccessView; }
        };
    }

    /** Releases the cached access views for one closed logical execution scope. */
    public void releaseScope(ExecutionHandle scopedExecution) {
        Objects.requireNonNull(scopedExecution, "Scoped execution cannot be null.");
        Object scopeIdentity = scopedExecution.scopeIdentity();
        scopedAccess.remove(scopeIdentity);
        scopedDurableAccess.remove(scopeIdentity);
    }

    private MessagingDataAccess newAccess(ExecutionHandle accessExecution, MessageRegistry registry) {
        RedisMessagingDataAccess delegate = new RedisMessagingDataAccess(
                () -> pool,
                accessExecution,
                logger,
                registry,
                maxSubscriptions,
                maxPayloadChars,
                maxQueuedMessagesPerHandler,
                handlerBatchSize,
                reconnectInitialBackoffMs,
                reconnectMaxBackoffMs,
                reconnectJitter,
                reconnectMaxAttempts
        );
        String accessId = Long.toUnsignedString(accessSequence.incrementAndGet(), 36);
        return new ObservableMessagingAccess(accessId, delegate);
    }

    private DurableMessagingDataAccess newDurableAccess(ExecutionHandle accessExecution, MessageRegistry registry) {
        return new RedisStreamsDurableMessagingDataAccess(
                () -> pool,
                accessExecution,
                logger,
                registry,
                maxPayloadChars,
                durableBatchSize,
                durableReadBlockMs,
                durableReclaimIdleMs,
                durableMaxAttempts,
                durableRetentionMs,
                durableRetentionMaxEntries,
                durableDeduplicationTtlSeconds,
                durableDeadLetterMaxEntries,
                durableRetentionTrimIntervalMs
        );
    }

    private static final class ObservableMessagingAccess implements MessagingDataAccess {
        private final String accessId;
        private final MessagingDataAccess delegate;

        private ObservableMessagingAccess(String accessId, MessagingDataAccess delegate) {
            this.accessId = Objects.requireNonNull(accessId, "Access id cannot be null.");
            this.delegate = Objects.requireNonNull(delegate, "Messaging delegate cannot be null.");
        }

        @Override
        public <T extends EventMessage> CompletableFuture<Void> publish(String destination, T message) {
            return delegate.publish(destination, message);
        }

        @Override
        public <T extends EventMessage> Subscription subscribe(
                String destination,
                String messageType,
                Class<T> type,
                Consumer<T> handler
        ) {
            Subscription subscription = delegate.subscribe(destination, messageType, type, handler);
            return new ObservableSubscription(accessId, destination, subscription);
        }

        @Override
        public List<SubscriptionSnapshot> subscriptions() {
            return delegate.subscriptions().stream()
                    .map(snapshot -> remapSnapshot(accessId, snapshot.destination(), snapshot, snapshot.state()))
                    .toList();
        }

        @Override
        public CompletableFuture<Void> shutdown() {
            return delegate.shutdown();
        }
    }

    private static final class ObservableSubscription implements Subscription {
        private final String logicalId;
        private final String destination;
        private final Subscription delegate;
        private final AtomicReference<SubscriptionSnapshot> terminalSnapshot = new AtomicReference<>();

        private ObservableSubscription(String accessId, String destination, Subscription delegate) {
            this.logicalId = compositeId(accessId, destination, delegate.id());
            this.destination = destination;
            this.delegate = Objects.requireNonNull(delegate, "Subscription delegate cannot be null.");
            delegate.completion().whenComplete((unused, failure) -> {
                if (failure != null) {
                    SubscriptionSnapshot snapshot = delegate.snapshot();
                    terminalSnapshot.compareAndSet(null, new SubscriptionSnapshot(
                            logicalId,
                            snapshot.destination(),
                            snapshot.messageType(),
                            SubscriptionState.FAILED,
                            snapshot.reconnectCount(),
                            snapshot.generation(),
                            snapshot.lastFailureAt(),
                            snapshot.lastFailure(),
                            Duration.ZERO,
                            snapshot.totalDowntime(),
                            false
                    ));
                }
            });
        }

        @Override
        public CompletableFuture<Void> unsubscribe() {
            return delegate.unsubscribe();
        }

        @Override
        public String id() {
            return logicalId;
        }

        @Override
        public SubscriptionState state() {
            return terminalSnapshot.get() == null ? delegate.state() : SubscriptionState.FAILED;
        }

        @Override
        public SubscriptionSnapshot snapshot() {
            SubscriptionSnapshot terminal = terminalSnapshot.get();
            if (terminal != null) {
                return terminal;
            }
            SubscriptionSnapshot snapshot = delegate.snapshot();
            return remapSnapshot(logicalId, destination, snapshot, snapshot.state());
        }

        @Override
        public CompletableFuture<Void> completion() {
            return delegate.completion();
        }
    }

    private static SubscriptionSnapshot remapSnapshot(
            String idPrefix,
            String destination,
            SubscriptionSnapshot snapshot,
            SubscriptionState state
    ) {
        String logicalId = idPrefix.contains(":")
                ? idPrefix
                : compositeId(idPrefix, destination, snapshot.logicalId());
        return new SubscriptionSnapshot(
                logicalId,
                snapshot.destination(),
                snapshot.messageType(),
                state,
                snapshot.reconnectCount(),
                snapshot.generation(),
                snapshot.lastFailureAt(),
                snapshot.lastFailure(),
                snapshot.currentDowntime(),
                snapshot.totalDowntime(),
                snapshot.activeListener()
        );
    }

    private static String compositeId(String accessId, String destination, String handlerId) {
        return accessId + ":" + destination + ":" + handlerId;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Redis messaging config '" + fieldName + "' cannot be null or blank.");
        }
        return value.trim();
    }

    private static String requireHost(String host) {
        String normalized = requireNonBlank(host, "host");
        if (!HOST_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "Redis messaging config 'host' contains unsupported characters: " + normalized);
        }
        return normalized;
    }

    private static int requireInRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("Redis messaging config '" + fieldName + "' must be between "
                    + min + " and " + max + ", but got " + value + ".");
        }
        return value;
    }

    private static long requireInRange(long value, long min, long max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("Redis messaging config '" + fieldName + "' must be between "
                    + min + " and " + max + ", but got " + value + ".");
        }
        return value;
    }

    private static double requireDoubleInRange(double value, double min, double max, String fieldName) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException("Redis messaging config '" + fieldName + "' must be between "
                    + min + " and " + max + ", but got " + value + ".");
        }
        return value;
    }
}
