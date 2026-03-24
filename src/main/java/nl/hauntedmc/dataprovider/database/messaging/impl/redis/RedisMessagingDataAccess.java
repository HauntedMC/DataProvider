package nl.hauntedmc.dataprovider.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.MessageRegistry;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;
import redis.clients.jedis.*;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

final class RedisMessagingDataAccess implements MessagingDataAccess {

    private final JedisPool pool;
    private final ExecutorService workers;
    private final Map<String, SubscriptionImpl> subs = new ConcurrentHashMap<>();

    RedisMessagingDataAccess(JedisPool pool, ExecutorService workers) {
        this.pool = pool;
        this.workers = workers;
    }

    @Override
    public <T extends EventMessage> CompletableFuture<Void> publish(String dest, T msg) {
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
        if (subs.containsKey(dest)) {
            return subs.get(dest);
        }

        JedisPubSub ps = new JedisPubSub() {
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

        Thread t = new Thread(() -> {
            try (Jedis j = pool.getResource()) {
                j.subscribe(ps, dest);
            } catch (Exception ex) {
                DataProvider.getLogger().error("Error while subscribing to " + dest, ex);
            }
        }, "redis-sub-" + dest);
        t.setDaemon(true);
        t.start();

        SubscriptionImpl sub = new SubscriptionImpl(ps, t);
        subs.put(dest, sub);
        return sub;
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        subs.values().forEach(SubscriptionImpl::unsubscribe);
        subs.clear();
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Private Subscription implementation
     */
        private record SubscriptionImpl(JedisPubSub ps, Thread thread) implements Subscription {

        @Override
            public CompletableFuture<Void> unsubscribe() {
                return CompletableFuture.runAsync(() -> {
                    ps.unsubscribe();
                    try {
                        thread.join(500);
                    } catch (InterruptedException ignored) {
                    }
                });
            }

            @Override
            public void close() {
                unsubscribe();
            }
        }
}
