package nl.hauntedmc.dataprovider.database.messaging.impl.rabbitmq;

import com.rabbitmq.client.Channel;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessageListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Implements MessagingDataAccess using RabbitMQ channels.
 */
public class RabbitMQMessagingDataAccess implements MessagingDataAccess {

    private final Channel channel;
    private final ExecutorService executor;

    public RabbitMQMessagingDataAccess(Channel channel, ExecutorService executor) {
        this.channel = channel;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<Void> sendEvent(String destination, String message) {
        return CompletableFuture.runAsync(() -> {
            try {
                // For simplicity, we publish to the default exchange with the routing key set to the destination.
                channel.basicPublish("", destination, null, message.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException("Failed to send message via RabbitMQ", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> subscribe(String destination, MessageListener listener) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Assume that 'destination' is the name of a queue.
                channel.basicConsume(destination, true, (consumerTag, delivery) -> {
                    String msg = new String(delivery.getBody(), StandardCharsets.UTF_8);
                    listener.onMessage(msg);
                }, consumerTag -> {
                    // Handle cancel notifications if needed.
                });
            } catch (IOException e) {
                throw new RuntimeException("Failed to subscribe to RabbitMQ queue", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> unsubscribe(String destination) {
        return CompletableFuture.runAsync(() -> {
            // In a full implementation, you would cancel the consumer using its consumer tag.
            // For this simplified example, we leave it as a no-op.
        }, executor);
    }
}
