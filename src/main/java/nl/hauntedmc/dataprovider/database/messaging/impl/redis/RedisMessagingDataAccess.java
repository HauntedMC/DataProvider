package nl.hauntedmc.dataprovider.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessageListener;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

/**
 * RedisMessagingDataAccess implements MessagingDataAccess using Redis Pub/Sub.
 */
public class RedisMessagingDataAccess implements MessagingDataAccess {

    private final JedisPool jedisPool;
    private final ExecutorService executor;
    private final Logger logger;

    public RedisMessagingDataAccess(JedisPool jedisPool, ExecutorService executor, Logger logger) {
        this.jedisPool = jedisPool;
        this.executor = executor;
        this.logger = logger;
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
            Jedis jedis = jedisPool.getResource();
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
                    try {
                        jedis.subscribe(pubSub, destination);
                    } catch (Exception e) {
                        logger.severe("Error in Redis subscription: " + e.getMessage());
                    } finally {
                        jedis.close();
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
