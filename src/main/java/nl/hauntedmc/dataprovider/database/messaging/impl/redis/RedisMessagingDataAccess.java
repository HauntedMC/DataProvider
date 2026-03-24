package nl.hauntedmc.dataprovider.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

final class RedisMessagingDataAccess implements MessagingDataAccess {

    private final JedisPool pool;
    private final ExecutorService workers;
    private final ILoggerAdapter logger;
    private final MessageRegistry messageRegistry;
    private final int maxSubscriptions;
    private final Map<String, ChannelSubscription> channelSubscriptions = new ConcurrentHashMap<>();
    private final Object subscriptionLock = new Object();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    RedisMessagingDataAccess(
            JedisPool pool,
            ExecutorService workers,
            ILoggerAdapter logger,
            MessageRegistry messageRegistry,
            int maxSubscriptions
    ) {
        this.pool = Objects.requireNonNull(pool, "Pool cannot be null");
        this.workers = Objects.requireNonNull(workers, "Workers cannot be null");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null");
        this.messageRegistry = Objects.requireNonNull(messageRegistry, "Message registry cannot be null");
        if (maxSubscriptions < 1) {
            throw new IllegalArgumentException("maxSubscriptions must be greater than zero");
        }
        this.maxSubscriptions = maxSubscriptions;
    }

    @Override
    public <T extends EventMessage> CompletableFuture<Void> publish(String dest, T msg) {
        Objects.requireNonNull(dest, "Destination cannot be null");
        Objects.requireNonNull(msg, "Message cannot be null");

        if (shuttingDown.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Messaging provider is shutting down"));
        }

        String json = messageRegistry.toJson(msg);
        return CompletableFuture.runAsync(() -> {
            try (Jedis j = pool.getResource()) {
                j.publish(dest, json);
            }
        }, workers);
    }

    @Override
    public <T extends EventMessage> Subscription subscribe(
            String dest, Class<T> type, Consumer<T> handler
    ) {
        Objects.requireNonNull(dest, "Destination cannot be null");
        Objects.requireNonNull(type, "Type cannot be null");
        Objects.requireNonNull(handler, "Handler cannot be null");

        if (shuttingDown.get()) {
            throw new IllegalStateException("Messaging provider is shutting down");
        }

        ChannelSubscription channelSubscription;
        boolean created = false;
        synchronized (subscriptionLock) {
            channelSubscription = channelSubscriptions.get(dest);
            if (channelSubscription == null) {
                if (channelSubscriptions.size() >= maxSubscriptions) {
                    throw new IllegalStateException("Maximum active Redis subscriptions reached (" + maxSubscriptions + ")");
                }
                channelSubscription = new ChannelSubscription(dest);
                channelSubscriptions.put(dest, channelSubscription);
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
                    for (HandlerRegistration<?> registration : handlers.values()) {
                        registration.dispatch(channel, raw);
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
                    handlers.clear();
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
            handlers.remove(handlerId);
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
            return CompletableFuture.runAsync(() -> {
                handlers.clear();
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
            }, workers);
        }
    }

    private final class HandlerRegistration<T extends EventMessage> {

        private final Class<T> type;
        private final Consumer<T> handler;

        private HandlerRegistration(Class<T> type, Consumer<T> handler) {
            this.type = Objects.requireNonNull(type, "Type cannot be null");
            this.handler = Objects.requireNonNull(handler, "Handler cannot be null");
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
    }
}
