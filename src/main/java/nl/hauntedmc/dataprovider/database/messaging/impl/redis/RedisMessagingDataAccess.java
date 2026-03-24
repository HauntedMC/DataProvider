package nl.hauntedmc.dataprovider.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;
import redis.clients.jedis.*;

import java.util.Objects;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class RedisMessagingDataAccess implements MessagingDataAccess {

    private final JedisPool pool;
    private final ExecutorService workers;
    private final Map<String, SubscriptionImpl> subs = new ConcurrentHashMap<>();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    RedisMessagingDataAccess(JedisPool pool, ExecutorService workers) {
        this.pool = pool;
        this.workers = workers;
    }

    @Override
    public <T extends EventMessage> CompletableFuture<Void> publish(String dest, T msg) {
        Objects.requireNonNull(dest, "Destination cannot be null");
        Objects.requireNonNull(msg, "Message cannot be null");

        if (shuttingDown.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Messaging provider is shutting down"));
        }

        String json = MessageRegistry.toJson(msg);
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

        return subs.computeIfAbsent(dest, channel -> {
            SubscriptionImpl created = new SubscriptionImpl(channel, type, handler);
            created.start();
            return created;
        });
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }

        SubscriptionImpl[] active = subs.values().toArray(new SubscriptionImpl[0]);
        CompletableFuture<?>[] futures = new CompletableFuture[active.length];
        for (int i = 0; i < active.length; i++) {
            futures[i] = active[i].unsubscribe();
        }

        return CompletableFuture.allOf(futures).whenComplete((unused, throwable) -> subs.clear());
    }

    /**
     * Private Subscription implementation
     */
    private final class SubscriptionImpl implements Subscription {

        private final String destination;
        private final JedisPubSub pubSub;
        private final Thread thread;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private <T extends EventMessage> SubscriptionImpl(String destination, Class<T> type, Consumer<T> handler) {
            this.destination = destination;
            this.pubSub = new JedisPubSub() {
                @Override
                public void onMessage(String channel, String raw) {
                    try {
                        T msg = MessageRegistry.fromJson(raw, type);
                        if (msg == null) {
                            DataProvider.getLogger().warn("Received null message while subscribing to channel " + channel);
                            return;
                        }
                        handler.accept(msg);
                    } catch (Exception ex) {
                        DataProvider.getLogger().error("Error while handling message from channel " + channel, ex);
                    }
                }
            };
            this.thread = new Thread(() -> {
                try (Jedis j = pool.getResource()) {
                    j.subscribe(pubSub, destination);
                } catch (Exception ex) {
                    if (!closed.get()) {
                        DataProvider.getLogger().error("Error while subscribing to " + destination, ex);
                    }
                } finally {
                    subs.remove(destination, this);
                }
            }, "redis-sub-" + destination);
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        @Override
        public CompletableFuture<Void> unsubscribe() {
            if (!closed.compareAndSet(false, true)) {
                return CompletableFuture.completedFuture(null);
            }
            subs.remove(destination, this);
            return CompletableFuture.runAsync(() -> {
                pubSub.unsubscribe();
                try {
                    thread.join(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, workers);
        }

        @Override
        public void close() {
            unsubscribe();
        }
    }
}
