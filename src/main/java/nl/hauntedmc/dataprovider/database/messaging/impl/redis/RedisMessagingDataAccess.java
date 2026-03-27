package nl.hauntedmc.dataprovider.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;
import nl.hauntedmc.dataprovider.internal.concurrent.AsyncTaskSupport;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;

final class RedisMessagingDataAccess implements MessagingDataAccess {

    private static final Pattern DESTINATION_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    private final JedisPool pool;
    private final ExecutorService workers;
    private final LoggerAdapter logger;
    private final MessageRegistry messageRegistry;
    private final int maxSubscriptions;
    private final int maxPayloadChars;
    private final int maxQueuedMessagesPerHandler;
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
        this.pool = Objects.requireNonNull(pool, "Pool cannot be null");
        this.workers = Objects.requireNonNull(workers, "Workers cannot be null");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "Message registry cannot be null");
        if (maxSubscriptions < 1) {
            throw new IllegalArgumentException("maxSubscriptions must be greater than zero");
        }
        this.maxSubscriptions = maxSubscriptions;
        if (maxPayloadChars < 1) {
            throw new IllegalArgumentException("maxPayloadChars must be greater than zero");
        }
        this.maxPayloadChars = maxPayloadChars;
        if (maxQueuedMessagesPerHandler < 1) {
            throw new IllegalArgumentException("maxQueuedMessagesPerHandler must be greater than zero");
        }
        this.maxQueuedMessagesPerHandler = maxQueuedMessagesPerHandler;
    }

    @Override
    public <T extends EventMessage> CompletableFuture<Void> publish(String dest, T msg) {
        String destination = validateDestination(dest);
        Objects.requireNonNull(msg, "Message cannot be null");

        if (shuttingDown.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Messaging provider is shutting down"));
        }

        String json = messageRegistry.toJson(msg);
        if (json.length() > maxPayloadChars) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Message payload exceeds maxPayloadChars (" + maxPayloadChars + ")"));
        }
        return AsyncTaskSupport.runAsync(workers, "redis.messaging.publish", () -> {
            try (Jedis j = pool.getResource()) {
                j.publish(destination, json);
            }
        });
    }

    @Override
    public <T extends EventMessage> Subscription subscribe(
            String dest, Class<T> type, Consumer<T> handler
    ) {
        String destination = validateDestination(dest);
        Objects.requireNonNull(type, "Type cannot be null");
        Objects.requireNonNull(handler, "Handler cannot be null");

        if (shuttingDown.get()) {
            throw new IllegalStateException("Messaging provider is shutting down");
        }

        ChannelSubscription channelSubscription;
        boolean created = false;
        synchronized (subscriptionLock) {
            channelSubscription = channelSubscriptions.get(destination);
            if (channelSubscription == null) {
                if (channelSubscriptions.size() >= maxSubscriptions) {
                    throw new IllegalStateException("Maximum active Redis subscriptions reached (" + maxSubscriptions + ")");
                }
                channelSubscription = new ChannelSubscription(destination);
                channelSubscriptions.put(destination, channelSubscription);
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

        ChannelSubscription[] active = channelSubscriptions.values().toArray(new ChannelSubscription[0]);
        CompletableFuture<?>[] futures = new CompletableFuture[active.length];
        for (int i = 0; i < active.length; i++) {
            futures[i] = active[i].unsubscribeChannel();
        }

        return CompletableFuture.allOf(futures).whenComplete((unused, throwable) -> channelSubscriptions.clear());
    }

    private final class ChannelSubscription {

        private final String destination;
        private final JedisPubSub pubSub;
        private final Thread thread;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicLong handlerSequence = new AtomicLong(0);
        private final Map<Long, HandlerRegistration<?>> handlers = new ConcurrentHashMap<>();

        private ChannelSubscription(String destination) {
            this.destination = destination;
            this.pubSub = new JedisPubSub() {
                @Override
                public void onMessage(String channel, String raw) {
                    if (raw == null || raw.isBlank()) {
                        logger.warn("Received empty message while subscribing to channel " + channel);
                        return;
                    }
                    if (raw.length() > maxPayloadChars) {
                        logger.warn("Dropped oversized message on channel " + channel + " (length="
                                + raw.length() + ", maxPayloadChars=" + maxPayloadChars + ")");
                        return;
                    }
                    for (HandlerRegistration<?> registration : handlers.values()) {
                        registration.enqueue(channel, raw);
                    }
                }
            };
            String channelThreadName = destination.length() > 48 ? destination.substring(0, 48) : destination;
            this.thread = new Thread(() -> {
                try (Jedis j = pool.getResource()) {
                    j.subscribe(pubSub, destination);
                } catch (Exception ex) {
                    if (!closed.get()) {
                        logger.error("Error while subscribing to " + destination, ex);
                    }
                } finally {
                    channelSubscriptions.remove(destination, this);
                    closeAndClearHandlers();
                    closed.set(true);
                }
            }, "redis-sub-" + channelThreadName);
            this.thread.setDaemon(true);
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
            if (!handlers.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            return unsubscribeChannel();
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
                    // Ignore best-effort unsubscribe exceptions during shutdown.
                }
                if (started.get() && Thread.currentThread() != thread) {
                    try {
                        thread.join(500);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        private void closeAndClearHandlers() {
            for (HandlerRegistration<?> registration : handlers.values()) {
                registration.close();
            }
            handlers.clear();
        }
    }

    private final class HandlerRegistration<T extends EventMessage> {

        private final Class<T> type;
        private final Consumer<T> handler;
        private final Object queueLock = new Object();
        private final ArrayDeque<QueuedMessage> queuedMessages = new ArrayDeque<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicLong droppedMessages = new AtomicLong(0L);
        private boolean workerScheduled;

        private HandlerRegistration(Class<T> type, Consumer<T> handler) {
            this.type = Objects.requireNonNull(type, "Type cannot be null");
            this.handler = Objects.requireNonNull(handler, "Handler cannot be null");
        }

        private void enqueue(String channel, String raw) {
            if (closed.get()) {
                return;
            }

            boolean shouldSchedule = false;
            synchronized (queueLock) {
                if (closed.get()) {
                    return;
                }
                if (queuedMessages.size() >= maxQueuedMessagesPerHandler) {
                    long dropped = droppedMessages.incrementAndGet();
                    if (dropped == 1 || dropped % 100 == 0) {
                        logger.warn("Dropped " + dropped + " queued message(s) for channel " + channel
                                + " because handler queue reached max_queued_messages_per_handler="
                                + maxQueuedMessagesPerHandler);
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
                workers.execute(this::drainQueue);
            } catch (RejectedExecutionException e) {
                synchronized (queueLock) {
                    workerScheduled = false;
                    queuedMessages.clear();
                }
                logger.warn("Dropped queued handler messages because dispatch worker pool is full.", e);
            }
        }

        private void drainQueue() {
            while (true) {
                QueuedMessage queuedMessage;
                synchronized (queueLock) {
                    if (closed.get()) {
                        queuedMessages.clear();
                        workerScheduled = false;
                        return;
                    }
                    queuedMessage = queuedMessages.pollFirst();
                    if (queuedMessage == null) {
                        workerScheduled = false;
                        return;
                    }
                }
                dispatch(queuedMessage.channel(), queuedMessage.raw());
            }
        }

        private void dispatch(String channel, String raw) {
            try {
                T msg = messageRegistry.fromJson(raw, type);
                if (msg == null) {
                    logger.warn("Received null message while subscribing to channel " + channel);
                    return;
                }
                handler.accept(msg);
            } catch (Exception ex) {
                logger.error("Error while handling message from channel " + channel, ex);
            }
        }

        private void close() {
            closed.set(true);
            synchronized (queueLock) {
                queuedMessages.clear();
                workerScheduled = false;
            }
        }
    }

    private record QueuedMessage(String channel, String raw) {
    }

    private static String validateDestination(String destination) {
        Objects.requireNonNull(destination, "Destination cannot be null");
        if (!DESTINATION_PATTERN.matcher(destination).matches()) {
            throw new IllegalArgumentException("Destination contains unsupported characters.");
        }
        return destination;
    }
}
