package nl.hauntedmc.dataprovider.core.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.core.concurrent.AsyncTaskSupport;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

final class RedisMessagingDataAccess implements MessagingDataAccess {

    private static final Pattern DESTINATION_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    private final JedisPool pool;
    private final Executor workers;
    private final ExecutionHandle executionBudget;
    private final LoggerAdapter logger;
    private final MessageRegistry messageRegistry;
    private final int maxSubscriptions;
    private final int maxPayloadChars;
    private final int maxQueuedMessagesPerHandler;
    private final int handlerBatchSize;
    private final Map<String, ChannelSubscription> channelSubscriptions = new ConcurrentHashMap<>();
    private final Object subscriptionLock = new Object();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    RedisMessagingDataAccess(
            JedisPool pool,
            ExecutorService workers,
            LoggerAdapter logger,
            MessageRegistry messageRegistry,
            int maxSubscriptions,
            int maxPayloadChars,
            int maxQueuedMessagesPerHandler
    ) {
        this(pool, workers, null, logger, messageRegistry, maxSubscriptions, maxPayloadChars,
                maxQueuedMessagesPerHandler, 64);
    }

    RedisMessagingDataAccess(
            JedisPool pool,
            ExecutionHandle workers,
            LoggerAdapter logger,
            MessageRegistry messageRegistry,
            int maxSubscriptions,
            int maxPayloadChars,
            int maxQueuedMessagesPerHandler,
            int handlerBatchSize
    ) {
        this(pool, workers, workers, logger, messageRegistry, maxSubscriptions, maxPayloadChars,
                maxQueuedMessagesPerHandler, handlerBatchSize);
    }

    private RedisMessagingDataAccess(
            JedisPool pool,
            Executor workers,
            ExecutionHandle executionBudget,
            LoggerAdapter logger,
            MessageRegistry messageRegistry,
            int maxSubscriptions,
            int maxPayloadChars,
            int maxQueuedMessagesPerHandler,
            int handlerBatchSize
    ) {
        this.pool = Objects.requireNonNull(pool, "Pool cannot be null");
        this.workers = Objects.requireNonNull(workers, "Workers cannot be null");
        this.executionBudget = executionBudget;
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "Message registry cannot be null");
        this.maxSubscriptions = requirePositive(maxSubscriptions, "maxSubscriptions");
        this.maxPayloadChars = requirePositive(maxPayloadChars, "maxPayloadChars");
        this.maxQueuedMessagesPerHandler = requirePositive(
                maxQueuedMessagesPerHandler, "maxQueuedMessagesPerHandler");
        this.handlerBatchSize = requirePositive(handlerBatchSize, "handlerBatchSize");
    }

    @Override
    public <T extends EventMessage> CompletableFuture<Void> publish(String destination, T message) {
        String validatedDestination = validateDestination(destination);
        Objects.requireNonNull(message, "Message cannot be null");
        if (shuttingDown.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Messaging provider is shutting down"));
        }
        String json = messageRegistry.toJson(message);
        if (json.length() > maxPayloadChars) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Message payload exceeds maxPayloadChars (" + maxPayloadChars + ")"));
        }
        return AsyncTaskSupport.runAsync(workers, "redis.messaging.publish", () -> {
            try (Jedis jedis = pool.getResource()) {
                jedis.publish(validatedDestination, json);
            }
        });
    }

    @Override
    public <T extends EventMessage> Subscription subscribe(
            String destination,
            Class<T> type,
            Consumer<T> handler
    ) {
        String validatedDestination = validateDestination(destination);
        Objects.requireNonNull(type, "Type cannot be null");
        Objects.requireNonNull(handler, "Handler cannot be null");
        if (shuttingDown.get()) {
            throw new IllegalStateException("Messaging provider is shutting down");
        }

        ChannelSubscription channelSubscription;
        boolean created = false;
        synchronized (subscriptionLock) {
            channelSubscription = channelSubscriptions.get(validatedDestination);
            if (channelSubscription == null) {
                if (channelSubscriptions.size() >= maxSubscriptions) {
                    throw new IllegalStateException(
                            "Maximum active Redis subscriptions reached (" + maxSubscriptions + ")");
                }
                if (executionBudget != null && !executionBudget.tryAcquireSubscription()) {
                    throw new IllegalStateException("DataProvider messaging subscription budget exhausted");
                }
                channelSubscription = new ChannelSubscription(validatedDestination, executionBudget != null);
                channelSubscriptions.put(validatedDestination, channelSubscription);
                created = true;
            }
        }

        Subscription subscription = channelSubscription.addHandler(type, handler);
        if (created) {
            channelSubscription.start();
        }
        return subscription;
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        ChannelSubscription[] active = channelSubscriptions.values().toArray(ChannelSubscription[]::new);
        CompletableFuture<?>[] futures = new CompletableFuture<?>[active.length];
        for (int index = 0; index < active.length; index++) {
            futures[index] = active[index].unsubscribeChannel();
        }
        return CompletableFuture.allOf(futures).whenComplete((unused, throwable) -> channelSubscriptions.clear());
    }

    private final class ChannelSubscription {
        private final String destination;
        private final JedisPubSub pubSub;
        private final Thread thread;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean budgetReleased = new AtomicBoolean(false);
        private final AtomicLong handlerSequence = new AtomicLong();
        private final Map<Long, HandlerRegistration<?>> handlers = new ConcurrentHashMap<>();
        private final boolean budgetHeld;

        private ChannelSubscription(String destination, boolean budgetHeld) {
            this.destination = destination;
            this.budgetHeld = budgetHeld;
            pubSub = new JedisPubSub() {
                @Override
                public void onMessage(String channel, String raw) {
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
            String threadName = destination.length() > 48 ? destination.substring(0, 48) : destination;
            thread = new Thread(this::listen, "redis-sub-" + threadName);
            thread.setDaemon(true);
        }

        private void listen() {
            try (Jedis jedis = pool.getResource()) {
                jedis.subscribe(pubSub, destination);
            } catch (Exception e) {
                if (!closed.get()) {
                    logger.error("Error while subscribing to " + destination, e);
                }
            } finally {
                channelSubscriptions.remove(destination, this);
                closeAndClearHandlers();
                closed.set(true);
                releaseBudget();
            }
        }

        private void start() {
            if (started.compareAndSet(false, true)) {
                thread.start();
            }
        }

        private <T extends EventMessage> Subscription addHandler(Class<T> type, Consumer<T> handler) {
            long handlerId = handlerSequence.incrementAndGet();
            handlers.put(handlerId, new HandlerRegistration<>(type, handler));
            return () -> removeHandler(handlerId);
        }

        private CompletableFuture<Void> removeHandler(long handlerId) {
            HandlerRegistration<?> removed = handlers.remove(handlerId);
            if (removed != null) {
                removed.close();
            }
            return handlers.isEmpty() ? unsubscribeChannel() : CompletableFuture.completedFuture(null);
        }

        private CompletableFuture<Void> unsubscribeChannel() {
            if (!closed.compareAndSet(false, true)) {
                return CompletableFuture.completedFuture(null);
            }
            channelSubscriptions.remove(destination, this);
            return AsyncTaskSupport.runAsync(workers, "redis.messaging.unsubscribeChannel", () -> {
                closeAndClearHandlers();
                try {
                    pubSub.unsubscribe();
                } catch (Exception ignored) {
                    // Best-effort during shutdown.
                }
                if (started.get() && Thread.currentThread() != thread) {
                    thread.join(500L);
                }
                releaseBudget();
            });
        }

        private void closeAndClearHandlers() {
            handlers.values().forEach(HandlerRegistration::close);
            handlers.clear();
        }

        private void releaseBudget() {
            if (budgetHeld && budgetReleased.compareAndSet(false, true)) {
                executionBudget.releaseSubscription();
            }
        }
    }

    private final class HandlerRegistration<T extends EventMessage> {
        private final Class<T> type;
        private final Consumer<T> handler;
        private final Object queueLock = new Object();
        private final ArrayDeque<QueuedMessage> queuedMessages = new ArrayDeque<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicLong droppedMessages = new AtomicLong();
        private boolean workerScheduled;

        private HandlerRegistration(Class<T> type, Consumer<T> handler) {
            this.type = Objects.requireNonNull(type, "Type cannot be null");
            this.handler = Objects.requireNonNull(handler, "Handler cannot be null");
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
            } catch (RejectedExecutionException e) {
                long dropped;
                synchronized (queueLock) {
                    dropped = queuedMessages.size();
                    queuedMessages.clear();
                    workerScheduled = false;
                }
                recordDropped(dropped);
                logger.warn("Dropped queued handler messages because dispatch capacity is full.", e);
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
                T message = messageRegistry.fromJson(raw, type);
                if (message == null) {
                    logger.warn("Received null message while subscribing to channel " + channel);
                    return;
                }
                handler.accept(message);
            } catch (Exception e) {
                logger.error("Error while handling message from channel " + channel, e);
            }
        }

        private void close() {
            closed.set(true);
            synchronized (queueLock) {
                long dropped = queuedMessages.size();
                queuedMessages.clear();
                workerScheduled = false;
                recordDropped(dropped);
            }
        }
    }

    private void recordDropped(long count) {
        if (count > 0 && executionBudget != null) {
            executionBudget.recordDroppedMessages(count);
        }
    }

    private static int requirePositive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be greater than zero");
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

    private record QueuedMessage(String channel, String raw) {
    }
}
