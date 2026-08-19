package nl.hauntedmc.dataprovider.core.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.core.concurrent.AsyncTaskSupport;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionRejectedException;
import nl.hauntedmc.dataprovider.core.exception.DataProviderExceptionMapper;
import nl.hauntedmc.dataprovider.core.exception.StructuredFailures;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;
import nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionSnapshot;
import nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionState;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import redis.clients.jedis.Connection;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.RedisClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Redis Pub/Sub access with durable logical subscriptions over replaceable physical listeners. */
final class RedisMessagingDataAccess implements MessagingDataAccess {

    private static final Pattern DESTINATION_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private static final long DEFAULT_INITIAL_BACKOFF_MS = 250L;
    private static final long DEFAULT_MAX_BACKOFF_MS = 10_000L;
    private static final double DEFAULT_JITTER = 0.20D;
    private static final PubSubRunner DEFAULT_PUB_SUB_RUNNER =
            (connection, listener, destination) -> listener.proceed(connection, destination);

    private final Supplier<RedisClient> clientSupplier;
    private final Executor workers;
    private final ExecutionHandle executionBudget;
    private final LoggerAdapter logger;
    private final MessageRegistry messageRegistry;
    private final int maxSubscriptions;
    private final int maxPayloadChars;
    private final int maxQueuedMessagesPerHandler;
    private final int handlerBatchSize;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final double reconnectJitter;
    private final int maxReconnectAttempts;
    private final PubSubRunner pubSubRunner;
    private final Map<String, ChannelSubscription> channelSubscriptions = new ConcurrentHashMap<>();
    private final Object subscriptionLock = new Object();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicReference<CompletableFuture<Void>> shutdownFuture = new AtomicReference<>();
    private final AtomicLong logicalSequence = new AtomicLong();

    RedisMessagingDataAccess(
            RedisClient client,
            ExecutorService workers,
            LoggerAdapter logger,
            MessageRegistry messageRegistry,
            int maxSubscriptions,
            int maxPayloadChars,
            int maxQueuedMessagesPerHandler
    ) {
        this(() -> client, workers, null, logger, messageRegistry, maxSubscriptions, maxPayloadChars,
                maxQueuedMessagesPerHandler, 64, DEFAULT_INITIAL_BACKOFF_MS,
                DEFAULT_MAX_BACKOFF_MS, DEFAULT_JITTER, 0, DEFAULT_PUB_SUB_RUNNER);
    }

    RedisMessagingDataAccess(
            RedisClient client,
            ExecutionHandle workers,
            LoggerAdapter logger,
            MessageRegistry messageRegistry,
            int maxSubscriptions,
            int maxPayloadChars,
            int maxQueuedMessagesPerHandler,
            int handlerBatchSize
    ) {
        this(() -> client, workers, workers, logger, messageRegistry, maxSubscriptions, maxPayloadChars,
                maxQueuedMessagesPerHandler, handlerBatchSize, DEFAULT_INITIAL_BACKOFF_MS,
                DEFAULT_MAX_BACKOFF_MS, DEFAULT_JITTER, 0, DEFAULT_PUB_SUB_RUNNER);
    }

    RedisMessagingDataAccess(
            Supplier<RedisClient> clientSupplier,
            ExecutionHandle workers,
            LoggerAdapter logger,
            MessageRegistry messageRegistry,
            int maxSubscriptions,
            int maxPayloadChars,
            int maxQueuedMessagesPerHandler,
            int handlerBatchSize,
            long initialBackoffMs,
            long maxBackoffMs,
            double reconnectJitter,
            int maxReconnectAttempts
    ) {
        this(clientSupplier, workers, workers, logger, messageRegistry, maxSubscriptions, maxPayloadChars,
                maxQueuedMessagesPerHandler, handlerBatchSize, initialBackoffMs, maxBackoffMs,
                reconnectJitter, maxReconnectAttempts, DEFAULT_PUB_SUB_RUNNER);
    }

    RedisMessagingDataAccess(
            Supplier<RedisClient> clientSupplier,
            ExecutionHandle workers,
            LoggerAdapter logger,
            MessageRegistry messageRegistry,
            int maxSubscriptions,
            int maxPayloadChars,
            int maxQueuedMessagesPerHandler,
            int handlerBatchSize,
            long initialBackoffMs,
            long maxBackoffMs,
            double reconnectJitter,
            int maxReconnectAttempts,
            PubSubRunner pubSubRunner
    ) {
        this(clientSupplier, workers, workers, logger, messageRegistry, maxSubscriptions, maxPayloadChars,
                maxQueuedMessagesPerHandler, handlerBatchSize, initialBackoffMs, maxBackoffMs,
                reconnectJitter, maxReconnectAttempts, pubSubRunner);
    }

    private RedisMessagingDataAccess(
            Supplier<RedisClient> clientSupplier,
            Executor workers,
            ExecutionHandle executionBudget,
            LoggerAdapter logger,
            MessageRegistry messageRegistry,
            int maxSubscriptions,
            int maxPayloadChars,
            int maxQueuedMessagesPerHandler,
            int handlerBatchSize,
            long initialBackoffMs,
            long maxBackoffMs,
            double reconnectJitter,
            int maxReconnectAttempts,
            PubSubRunner pubSubRunner
    ) {
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "Client supplier cannot be null");
        this.workers = Objects.requireNonNull(workers, "Workers cannot be null");
        this.executionBudget = executionBudget;
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "Message registry cannot be null");
        this.maxSubscriptions = requirePositive(maxSubscriptions, "maxSubscriptions");
        this.maxPayloadChars = requirePositive(maxPayloadChars, "maxPayloadChars");
        this.maxQueuedMessagesPerHandler = requirePositive(
                maxQueuedMessagesPerHandler, "maxQueuedMessagesPerHandler");
        this.handlerBatchSize = requirePositive(handlerBatchSize, "handlerBatchSize");
        this.initialBackoffMs = requireNonNegative(initialBackoffMs, "initialBackoffMs");
        this.maxBackoffMs = requireAtLeast(maxBackoffMs, this.initialBackoffMs, "maxBackoffMs");
        if (reconnectJitter < 0.0D || reconnectJitter > 1.0D || !Double.isFinite(reconnectJitter)) {
            throw new IllegalArgumentException("reconnectJitter must be between zero and one");
        }
        this.reconnectJitter = reconnectJitter;
        if (maxReconnectAttempts < 0) {
            throw new IllegalArgumentException("maxReconnectAttempts cannot be negative");
        }
        this.maxReconnectAttempts = maxReconnectAttempts;
        this.pubSubRunner = Objects.requireNonNull(pubSubRunner, "Pub/Sub runner cannot be null");
    }

    @Override
    public <T extends EventMessage> CompletableFuture<Void> publish(String destination, T message) {
        String validatedDestination = validateDestination(destination);
        Objects.requireNonNull(message, "Message cannot be null");
        if (shuttingDown.get()) {
            return CompletableFuture.failedFuture(StructuredFailures.closed(workers, "redis.messaging.publish"));
        }
        final String json;
        try {
            json = messageRegistry.toJson(message);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(
                    StructuredFailures.serialization(failure, workers, "redis.messaging.serialize"));
        }
        if (json.length() > maxPayloadChars) {
            return CompletableFuture.failedFuture(StructuredFailures.serialization(
                    new IllegalArgumentException("Serialized message exceeds configured size limit."),
                    workers,
                    "redis.messaging.serialize"
            ));
        }
        return AsyncTaskSupport.runAsync(workers, "redis.messaging.publish",
                () -> requireClient().publish(validatedDestination, json));
    }

    @Override
    public <T extends EventMessage> Subscription subscribe(
            String destination,
            String messageType,
            Class<T> type,
            Consumer<T> handler
    ) {
        String validatedDestination = validateDestination(destination);
        String validatedMessageType = MessageRegistry.validateType(messageType);
        Objects.requireNonNull(type, "Type cannot be null");
        Objects.requireNonNull(handler, "Handler cannot be null");
        if (shuttingDown.get()) {
            throw StructuredFailures.closed(workers, "redis.messaging.subscribe");
        }

        ChannelSubscription channelSubscription;
        Subscription subscription;
        boolean created = false;
        synchronized (subscriptionLock) {
            if (shuttingDown.get()) {
                throw StructuredFailures.closed(workers, "redis.messaging.subscribe");
            }
            channelSubscription = channelSubscriptions.get(validatedDestination);
            if (channelSubscription != null && !channelSubscription.acceptingHandlers()) {
                channelSubscriptions.remove(validatedDestination, channelSubscription);
                channelSubscription = null;
            }
            if (channelSubscription == null) {
                if (channelSubscriptions.size() >= maxSubscriptions) {
                    throw subscriptionLimitFailure("Connection subscription limit reached.");
                }
                if (executionBudget != null && !executionBudget.tryAcquireSubscription()) {
                    throw subscriptionLimitFailure("Runtime subscription limit reached.");
                }
                String logicalId = Long.toUnsignedString(logicalSequence.incrementAndGet(), 36);
                channelSubscription = new ChannelSubscription(validatedDestination, logicalId, executionBudget != null);
                channelSubscriptions.put(validatedDestination, channelSubscription);
                created = true;
            }
            subscription = channelSubscription.addHandler(validatedMessageType, type, handler);
        }
        if (created) {
            channelSubscription.start();
        }
        return subscription;
    }

    @Override
    public List<SubscriptionSnapshot> subscriptions() {
        List<SubscriptionSnapshot> snapshots = new ArrayList<>();
        channelSubscriptions.values().forEach(subscription -> subscription.addSnapshots(snapshots));
        return List.copyOf(snapshots);
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
        ChannelSubscription[] active;
        synchronized (subscriptionLock) {
            active = channelSubscriptions.values().toArray(ChannelSubscription[]::new);
        }
        CompletableFuture<?>[] futures = new CompletableFuture<?>[active.length];
        try {
            for (int index = 0; index < active.length; index++) {
                futures[index] = active[index].unsubscribeChannel();
            }
        } catch (Throwable failure) {
            channelSubscriptions.clear();
            created.completeExceptionally(failure);
            return created;
        }
        CompletableFuture.allOf(futures).whenComplete((unused, throwable) -> {
            channelSubscriptions.clear();
            if (throwable == null) {
                created.complete(null);
            } else {
                created.completeExceptionally(throwable);
            }
        });
        return created;
    }

    int activeListenerCount() {
        return channelSubscriptions.values().stream()
                .mapToInt(ChannelSubscription::activeListenerCount)
                .sum();
    }

    int logicalSubscriptionCount() {
        return channelSubscriptions.size();
    }

    private RuntimeException subscriptionLimitFailure(String message) {
        return DataProviderExceptionMapper.translate(
                new ExecutionRejectedException(ExecutionRejectedException.Reason.SUBSCRIPTION_LIMIT, message),
                workers,
                "redis.messaging.subscribe"
        );
    }

    private RedisClient requireClient() {
        RedisClient client = clientSupplier.get();
        if (client == null || client.getPool().isClosed()) {
            throw new IllegalStateException("Redis messaging client is temporarily unavailable.");
        }
        return client;
    }

    private final class ChannelSubscription {
        private final String destination;
        private final String logicalId;
        private final Thread supervisor;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean closing = new AtomicBoolean(false);
        private final AtomicBoolean budgetReleased = new AtomicBoolean(false);
        private final AtomicReference<SubscriptionState> state = new AtomicReference<>(SubscriptionState.CONNECTING);
        private final AtomicReference<ListenerAttempt> listenerAttempt = new AtomicReference<>();
        private final CompletableFuture<Void> terminated = new CompletableFuture<>();
        private final AtomicLong handlerSequence = new AtomicLong();
        private final Map<Long, HandlerRegistration<?>> handlers = new ConcurrentHashMap<>();
        private final AtomicLong generation = new AtomicLong();
        private final AtomicLong reconnectCount = new AtomicLong();
        private final AtomicReference<Throwable> lastFailure = new AtomicReference<>();
        private final AtomicReference<Instant> lastFailureAt = new AtomicReference<>();
        private final AtomicLong downtimeStartedNanos = new AtomicLong(-1L);
        private final AtomicLong totalDowntimeNanos = new AtomicLong();
        private final AtomicInteger activeListeners = new AtomicInteger();
        private final boolean budgetHeld;

        private ChannelSubscription(String destination, String logicalId, boolean budgetHeld) {
            this.destination = destination;
            this.logicalId = logicalId;
            this.budgetHeld = budgetHeld;
            String threadName = destination.length() > 48 ? destination.substring(0, 48) : destination;
            supervisor = new Thread(this::supervise, "redis-sub-" + threadName + "-" + logicalId);
            supervisor.setDaemon(true);
        }

        private boolean acceptingHandlers() {
            SubscriptionState current = state.get();
            return !closing.get() && current != SubscriptionState.CLOSED && current != SubscriptionState.FAILED;
        }

        private void start() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            if (closing.get()) {
                finishClosed();
                return;
            }
            try {
                supervisor.start();
            } catch (RuntimeException failure) {
                failTerminal(failure);
            }
        }

        private void supervise() {
            int consecutiveFailures = 0;
            boolean firstAttempt = true;
            try {
                while (!closing.get()) {
                    if (!firstAttempt) {
                        reconnectCount.incrementAndGet();
                        setRecovering();
                        if (!awaitBackoff(consecutiveFailures)) {
                            break;
                        }
                    }
                    firstAttempt = false;
                    if (closing.get()) {
                        break;
                    }

                    long attemptGeneration = generation.incrementAndGet();
                    ListenerAttempt attempt = new ListenerAttempt(attemptGeneration);
                    listenerAttempt.set(attempt);
                    try (Connection connection = requireClient().getPool().getResource()) {
                        attempt.connection.set(connection);
                        if (closing.get() || !isCurrent(attempt)) {
                            continue;
                        }
                        pubSubRunner.subscribe(connection, attempt.pubSub, destination);
                        if (!closing.get() && isCurrent(attempt)) {
                            throw new IllegalStateException("Redis Pub/Sub listener ended unexpectedly.");
                        }
                    } catch (Exception failure) {
                        if (closing.get() || !isCurrent(attempt)) {
                            continue;
                        }
                        boolean hadBeenActive = attempt.active.get();
                        consecutiveFailures = hadBeenActive ? 1 : consecutiveFailures + 1;
                        recordFailure(failure);
                        beginDowntime();
                        if (isTerminalFailure(failure)) {
                            failTerminal(failure);
                            return;
                        }
                    } finally {
                        attempt.deactivate();
                        listenerAttempt.compareAndSet(attempt, null);
                    }
                }
            } finally {
                ListenerAttempt attempt = listenerAttempt.getAndSet(null);
                if (attempt != null) {
                    attempt.stop();
                    attempt.deactivate();
                }
                if (state.get() != SubscriptionState.FAILED) {
                    finishClosed();
                }
            }
        }

        private boolean awaitBackoff(int consecutiveFailures) {
            long delay = reconnectDelayMillis(Math.max(1, consecutiveFailures));
            if (delay == 0L) {
                return !closing.get();
            }
            try {
                Thread.sleep(delay);
                return !closing.get();
            } catch (InterruptedException interrupted) {
                if (!closing.get()) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }
        }

        private long reconnectDelayMillis(int consecutiveFailures) {
            int shift = Math.min(30, Math.max(0, consecutiveFailures - 1));
            long exponential;
            try {
                exponential = Math.multiplyExact(initialBackoffMs, 1L << shift);
            } catch (ArithmeticException overflow) {
                exponential = maxBackoffMs;
            }
            long capped = Math.min(maxBackoffMs, exponential);
            if (capped == 0L || reconnectJitter == 0.0D) {
                return capped;
            }
            double factor = ThreadLocalRandom.current().nextDouble(
                    Math.max(0.0D, 1.0D - reconnectJitter), 1.0D + reconnectJitter);
            return Math.max(0L, Math.min(maxBackoffMs, Math.round(capped * factor)));
        }

        private boolean isTerminalFailure(Throwable failure) {
            if (RedisRetryClassifier.isTerminal(failure)) {
                return true;
            }
            return maxReconnectAttempts > 0 && reconnectCount.get() >= maxReconnectAttempts;
        }

        private void recordFailure(Throwable failure) {
            lastFailure.set(failure);
            lastFailureAt.set(Instant.now());
            long reconnects = reconnectCount.get();
            if (reconnects == 0L || reconnects == 1L || reconnects % 10L == 0L) {
                logger.warn("Redis subscription " + logicalId + " for " + destination
                        + " lost its listener; automatic reconnect is active.", failure);
            }
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

        private void setActive(ListenerAttempt attempt) {
            if (!closing.get() && isCurrent(attempt)) {
                endDowntime();
                state.updateAndGet(current -> switch (current) {
                    case CONNECTING, RECONNECTING -> SubscriptionState.ACTIVE;
                    case ACTIVE, CLOSING, CLOSED, FAILED -> current;
                });
            }
        }

        private boolean isCurrent(ListenerAttempt attempt) {
            return generation.get() == attempt.generation && listenerAttempt.get() == attempt;
        }

        private <T extends EventMessage> Subscription addHandler(
                String messageType,
                Class<T> type,
                Consumer<T> handler
        ) {
            if (!acceptingHandlers()) {
                throw StructuredFailures.closed(workers, "redis.messaging.subscribe");
            }
            long handlerId = handlerSequence.incrementAndGet();
            HandlerRegistration<T> registration = new HandlerRegistration<>(handlerId, messageType, type, handler);
            handlers.put(handlerId, registration);
            return new SubscriptionHandle(registration);
        }

        private CompletableFuture<Void> removeHandler(long handlerId) {
            HandlerRegistration<?> removed = handlers.remove(handlerId);
            if (removed != null) {
                removed.closeNormally();
            }
            return handlers.isEmpty() ? unsubscribeChannel() : CompletableFuture.completedFuture(null);
        }

        private CompletableFuture<Void> unsubscribeChannel() {
            SubscriptionState current = state.get();
            if (current == SubscriptionState.CLOSED || current == SubscriptionState.FAILED) {
                return terminated;
            }
            if (!closing.compareAndSet(false, true)) {
                return terminated;
            }
            state.updateAndGet(previous -> previous == SubscriptionState.FAILED
                    ? previous : SubscriptionState.CLOSING);
            channelSubscriptions.remove(destination, this);
            closeAndClearHandlers(null);
            ListenerAttempt attempt = listenerAttempt.get();
            if (attempt != null) {
                attempt.stop();
            }
            supervisor.interrupt();
            if (!started.get()) {
                finishClosed();
            }
            return terminated;
        }

        private void failTerminal(Throwable failure) {
            if (closing.get()) {
                finishClosed();
                return;
            }
            state.set(SubscriptionState.FAILED);
            channelSubscriptions.remove(destination, this);
            closeAndClearHandlers(failure);
            ListenerAttempt attempt = listenerAttempt.getAndSet(null);
            if (attempt != null) {
                attempt.stop();
                attempt.deactivate();
            }
            releaseBudget();
            logger.error("Redis subscription " + logicalId + " for " + destination
                    + " reached a terminal failure and will not reconnect.", failure);
            terminated.completeExceptionally(failure);
        }

        private void finishClosed() {
            if (state.get() != SubscriptionState.FAILED) {
                state.set(SubscriptionState.CLOSED);
                endDowntime();
                releaseBudget();
                terminated.complete(null);
            }
        }

        private void closeAndClearHandlers(Throwable failure) {
            handlers.values().forEach(registration -> {
                if (failure == null) {
                    registration.closeNormally();
                } else {
                    registration.closeExceptionally(failure);
                }
            });
            handlers.clear();
        }

        private void releaseBudget() {
            if (budgetHeld && budgetReleased.compareAndSet(false, true)) {
                executionBudget.releaseSubscription();
            }
        }

        private void addSnapshots(List<SubscriptionSnapshot> snapshots) {
            handlers.values().forEach(registration -> snapshots.add(snapshot(registration)));
        }

        private SubscriptionSnapshot snapshot(HandlerRegistration<?> registration) {
            long currentDowntime = currentDowntimeNanos();
            Throwable failure = lastFailure.get();
            return new SubscriptionSnapshot(
                    registration.logicalId(),
                    destination,
                    registration.type.getName(),
                    registration.closed.get() ? SubscriptionState.CLOSED : state.get(),
                    reconnectCount.get(),
                    generation.get(),
                    lastFailureAt.get(),
                    describeFailure(failure),
                    Duration.ofNanos(currentDowntime),
                    Duration.ofNanos(totalDowntimeNanos.get() + currentDowntime),
                    activeListeners.get() == 1 && state.get() == SubscriptionState.ACTIVE
            );
        }

        private long currentDowntimeNanos() {
            long startedAt = downtimeStartedNanos.get();
            return startedAt < 0L ? 0L : Math.max(0L, System.nanoTime() - startedAt);
        }

        private int activeListenerCount() {
            return activeListeners.get();
        }

        private final class ListenerAttempt {
            private final long generation;
            private final AtomicReference<Connection> connection = new AtomicReference<>();
            private final AtomicBoolean active = new AtomicBoolean(false);
            private final JedisPubSub pubSub = new JedisPubSub() {
                @Override
                public void onSubscribe(String channel, int subscribedChannels) {
                    if (isCurrent(ListenerAttempt.this) && active.compareAndSet(false, true)) {
                        activeListeners.incrementAndGet();
                        setActive(ListenerAttempt.this);
                    }
                }

                @Override
                public void onUnsubscribe(String channel, int subscribedChannels) {
                    deactivate();
                }

                @Override
                public void onMessage(String channel, String raw) {
                    if (!isCurrent(ListenerAttempt.this) || state.get() != SubscriptionState.ACTIVE) {
                        return;
                    }
                    if (raw == null || raw.isBlank()) {
                        logger.warn("Received empty message while subscribing to channel " + channel);
                        return;
                    }
                    if (raw.length() > maxPayloadChars) {
                        recordDropped(1);
                        logger.warn("Dropped oversized message on channel " + channel);
                        return;
                    }
                    handlers.values().forEach(registration -> registration.enqueue(channel, raw));
                }
            };

            private ListenerAttempt(long generation) {
                this.generation = generation;
            }

            private void stop() {
                try {
                    pubSub.unsubscribe();
                } catch (Exception ignored) {
                    // Force-disconnecting the connection below is the final interruption mechanism.
                }
                Connection listener = connection.getAndSet(null);
                if (listener != null) {
                    try {
                        listener.forceDisconnect();
                    } catch (Exception ignored) {
                        // The supervisor owns final resource cleanup and pool invalidation.
                    }
                }
            }

            private void deactivate() {
                if (active.compareAndSet(true, false)) {
                    activeListeners.decrementAndGet();
                }
            }
        }

        private final class SubscriptionHandle implements Subscription {
            private final HandlerRegistration<?> registration;

            private SubscriptionHandle(HandlerRegistration<?> registration) {
                this.registration = registration;
            }

            @Override
            public CompletableFuture<Void> unsubscribe() {
                return removeHandler(registration.handlerId);
            }

            @Override
            public String id() {
                return registration.logicalId();
            }

            @Override
            public SubscriptionState state() {
                return registration.closed.get() ? SubscriptionState.CLOSED : state.get();
            }

            @Override
            public SubscriptionSnapshot snapshot() {
                return ChannelSubscription.this.snapshot(registration);
            }

            @Override
            public CompletableFuture<Void> completion() {
                return registration.completion;
            }
        }
    }

    private final class HandlerRegistration<T extends EventMessage> {
        private final long handlerId;
        private final String messageType;
        private final Class<T> type;
        private final Consumer<T> handler;
        private final Object queueLock = new Object();
        private final ArrayDeque<QueuedMessage> queuedMessages = new ArrayDeque<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicLong droppedMessages = new AtomicLong();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private boolean workerScheduled;

        private HandlerRegistration(long handlerId, String messageType, Class<T> type, Consumer<T> handler) {
            this.handlerId = handlerId;
            this.messageType = Objects.requireNonNull(messageType, "Message type cannot be null");
            this.type = Objects.requireNonNull(type, "Type cannot be null");
            this.handler = Objects.requireNonNull(handler, "Handler cannot be null");
        }

        private String logicalId() {
            return Long.toUnsignedString(handlerId, 36);
        }

        private void enqueue(String channel, String raw) {
            boolean shouldSchedule = false;
            synchronized (queueLock) {
                if (closed.get()) {
                    return;
                }
                if (queuedMessages.size() >= maxQueuedMessagesPerHandler) {
                    long dropped = droppedMessages.incrementAndGet();
                    recordDropped(1);
                    if (dropped == 1 || dropped % 100 == 0) {
                        logger.warn("Dropped " + dropped + " queued message(s) for channel " + channel);
                    }
                    return;
                }
                queuedMessages.addLast(new QueuedMessage(channel, raw));
                if (!workerScheduled) {
                    workerScheduled = true;
                    shouldSchedule = true;
                }
            }
            if (shouldSchedule) {
                scheduleDrain();
            }
        }

        private void scheduleDrain() {
            try {
                workers.execute(this::drainBatch);
            } catch (RejectedExecutionException failure) {
                long dropped;
                synchronized (queueLock) {
                    dropped = queuedMessages.size();
                    queuedMessages.clear();
                    workerScheduled = false;
                }
                recordDropped(dropped);
                logger.warn("Dropped queued handler messages because dispatch capacity is full.", failure);
            }
        }

        private void drainBatch() {
            int processed = 0;
            while (processed < handlerBatchSize) {
                QueuedMessage queued;
                synchronized (queueLock) {
                    if (closed.get()) {
                        queuedMessages.clear();
                        workerScheduled = false;
                        return;
                    }
                    queued = queuedMessages.pollFirst();
                    if (queued == null) {
                        workerScheduled = false;
                        return;
                    }
                }
                dispatch(queued.channel(), queued.raw());
                processed++;
            }
            synchronized (queueLock) {
                if (closed.get() || queuedMessages.isEmpty()) {
                    workerScheduled = false;
                    return;
                }
            }
            scheduleDrain();
        }

        private void dispatch(String channel, String raw) {
            try {
                T message = messageRegistry.fromJson(raw, type, messageType);
                handler.accept(message);
            } catch (VirtualMachineError fatal) {
                closeExceptionally(fatal);
                throw fatal;
            } catch (Throwable failure) {
                logger.error("Error while handling message from channel " + channel, failure);
            }
        }

        private void closeNormally() {
            if (closeQueue()) {
                completion.complete(null);
            }
        }

        private void closeExceptionally(Throwable failure) {
            if (closeQueue()) {
                completion.completeExceptionally(failure);
            }
        }

        private boolean closeQueue() {
            if (!closed.compareAndSet(false, true)) {
                return false;
            }
            synchronized (queueLock) {
                long dropped = queuedMessages.size();
                queuedMessages.clear();
                workerScheduled = false;
                recordDropped(dropped);
            }
            return true;
        }
    }

    private void recordDropped(long count) {
        if (count > 0 && executionBudget != null) {
            executionBudget.recordDroppedMessages(count);
        }
    }

    private static String describeFailure(Throwable failure) {
        if (failure == null) {
            return null;
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            return failure.getClass().getName();
        }
        String normalized = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (normalized.length() > 256) {
            normalized = normalized.substring(0, 256);
        }
        return failure.getClass().getName() + ": " + normalized;
    }

    private static int requirePositive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be greater than zero");
        }
        return value;
    }

    private static long requireNonNegative(long value, String field) {
        if (value < 0L) {
            throw new IllegalArgumentException(field + " cannot be negative");
        }
        return value;
    }

    private static long requireAtLeast(long value, long minimum, String field) {
        if (value < minimum) {
            throw new IllegalArgumentException(field + " must be at least " + minimum);
        }
        return value;
    }

    private static String validateDestination(String destination) {
        Objects.requireNonNull(destination, "Destination cannot be null");
        if (!DESTINATION_PATTERN.matcher(destination).matches()) {
            throw new IllegalArgumentException("Destination contains unsupported characters.");
        }
        return destination;
    }

    @FunctionalInterface
    interface PubSubRunner {
        void subscribe(Connection connection, JedisPubSub listener, String destination) throws Exception;
    }

    private record QueuedMessage(String channel, String raw) {
    }
}
