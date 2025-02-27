package nl.hauntedmc.dataprovider.database.messaging.impl.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import nl.hauntedmc.dataprovider.DataProviderApp;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * RabbitMQMessagingDatabase implements MessagingDatabaseProvider using RabbitMQ.
 */
public class RabbitMQMessagingDatabase implements MessagingDatabaseProvider {

    private final CommentedConfigurationNode config;
    private Connection connection;
    private Channel channel;
    private ExecutorService executor;
    private RabbitMQMessagingDataAccess dataAccess;
    private boolean connected;

    public RabbitMQMessagingDatabase(CommentedConfigurationNode config) {
        this.config = config;
    }

    @Override
    public void connect() {
        if (connected && connection != null) {
            DataProviderApp.getLogger().info("[RabbitMQMessagingDatabase] Already connected; skipping re–initialization.");
            return;
        }
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(config.node("host").getString("localhost"));
            factory.setPort(config.node("port").getInt(5672));
            factory.setUsername(config.node("username").getString("guest"));
            factory.setPassword(config.node("password").getString("guest"));
            // Optionally set virtual host or other settings here

            connection = factory.newConnection();
            channel = connection.createChannel();

            int poolSize = config.node("pool_size").getInt(4);
            executor = Executors.newFixedThreadPool(poolSize);

            dataAccess = new RabbitMQMessagingDataAccess(channel, executor);
            connected = true;
            DataProviderApp.getLogger().info("[RabbitMQMessagingDatabase] Connected successfully to RabbitMQ.");
        } catch (IOException | TimeoutException e) {
            DataProviderApp.getLogger().error("[RabbitMQMessagingDatabase] Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void disconnect() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
                DataProviderApp.getLogger().info("[RabbitMQMessagingDatabase] Channel closed.");
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
                DataProviderApp.getLogger().info("[RabbitMQMessagingDatabase] Connection closed.");
            }
            if (executor != null && !executor.isShutdown()) {
                executor.shutdown();
                DataProviderApp.getLogger().info("[RabbitMQMessagingDatabase] ExecutorService shut down.");
            }
        } catch (IOException | TimeoutException e) {
            DataProviderApp.getLogger().error("[RabbitMQMessagingDatabase] Error during disconnect: " + e.getMessage());
            e.printStackTrace();
        }
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected && connection != null && connection.isOpen();
    }

    @Override
    public MessagingDataAccess getDataAccess() {
        return dataAccess;
    }
}
