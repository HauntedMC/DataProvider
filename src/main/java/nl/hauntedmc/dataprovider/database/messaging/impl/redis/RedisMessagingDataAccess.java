package nl.hauntedmc.dataprovider.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessageListener;
import nl.hauntedmc.dataprovider.logger.DPLogger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * RedisMessagingDataAccess implements MessagingDataAccess using Redis Pub/Sub.
 */
public class RedisMessagingDataAccess implements MessagingDataAccess {

    private final JedisPool jedisPool;
    private final ExecutorService executor;

    public RedisMessagingDataAccess(JedisPool jedisPool, ExecutorService executor) {
        this.jedisPool = jedisPool;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Void> sendEvent(String destination, String message) {
        return CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.publish(destination, message);
            } catch (Exception e) {
                throw new RuntimeException("Failed to send message via Redis", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> subscribe(String destination, MessageListener listener) {
        return CompletableFuture.runAsync(() -> {
            // Create a dedicated Jedis instance for subscription.
            // Create a new JedisPubSub instance to handle messages.
            JedisPubSub pubSub = new JedisPubSub() {
                @Override
                public void onMessage(String channel, String message) {
                    if (channel.equals(destination)) {
                        listener.onMessage(message);
                    }
                }
            };
            try {
                // Run the subscription in a separate thread since subscribe() is blocking.
                executor.submit(() -> {
                    try (Jedis jedis = jedisPool.getResource()) {
                        jedis.subscribe(pubSub, destination);
                    } catch (Exception e) {
                        DPLogger.error("Error in Redis subscription: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                throw new RuntimeException("Failed to subscribe to Redis channel", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> unsubscribe(String destination) {
        return CompletableFuture.runAsync(() -> {
            // In a full implementation you would retain a reference to your JedisPubSub and call unsubscribe().
            // For this simplified example, this method is a no-op.
        }, executor);
    }
}
