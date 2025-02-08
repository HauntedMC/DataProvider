package nl.hauntedmc.dataprovider.database.messaging.impl.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * RabbitMQMessagingDatabase implements MessagingDatabaseProvider using RabbitMQ.
 */
public class RabbitMQMessagingDatabase implements MessagingDatabaseProvider {

    private final FileConfiguration config;
    private final Logger logger;

    private Connection connection;
    private Channel channel;
    private ExecutorService executor;
    private RabbitMQMessagingDataAccess dataAccess;
    private boolean connected;

    public RabbitMQMessagingDatabase(FileConfiguration config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public RabbitMQMessagingDatabase(FileConfiguration config) {
        this(config, Logger.getLogger("RabbitMQMessagingDatabase"));
    }

    @Override
    public void connect() {
        if (connected && connection != null) {
            logger.info("[RabbitMQMessagingDatabase] Already connected; skipping re–initialization.");
            return;
        }
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(config.getString("host", "localhost"));
            factory.setPort(config.getInt("port", 5672));
            factory.setUsername(config.getString("username", "guest"));
            factory.setPassword(config.getString("password", "guest"));
            // Optionally set virtual host, etc.

            connection = factory.newConnection();
            channel = connection.createChannel();

            int poolSize = config.getInt("pool_size", 4);
            executor = Executors.newFixedThreadPool(poolSize);

            dataAccess = new RabbitMQMessagingDataAccess(channel, executor, logger);
            connected = true;
            logger.info("[RabbitMQMessagingDatabase] Connected successfully to RabbitMQ.");
        } catch (IOException | TimeoutException e) {
            logger.severe("[RabbitMQMessagingDatabase] Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void disconnect() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
                logger.info("[RabbitMQMessagingDatabase] Channel closed.");
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
                logger.info("[RabbitMQMessagingDatabase] Connection closed.");
            }
            if (executor != null && !executor.isShutdown()) {
                executor.shutdown();
                logger.info("[RabbitMQMessagingDatabase] ExecutorService shut down.");
            }
        } catch (IOException | TimeoutException e) {
            logger.severe("[RabbitMQMessagingDatabase] Error during disconnect: " + e.getMessage());
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
