package nl.hauntedmc.dataprovider.database.messaging.impl.kafka;

import nl.hauntedmc.dataprovider.DataProviderApp;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * KafkaMessagingDatabase implements MessagingDatabaseProvider using Kafka.
 * This version uses Configurate (CommentedConfigurationNode) to load configuration values.
 */
public class KafkaMessagingDatabase implements MessagingDatabaseProvider {

    private final CommentedConfigurationNode config;
    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> consumer;
    private ExecutorService consumerExecutor;
    private KafkaMessagingDataAccess dataAccess;
    private boolean connected;

    public KafkaMessagingDatabase(CommentedConfigurationNode config) {
        this.config = config;
    }

    @Override
    public void connect() {
        if (connected) {
            DataProviderApp.getLogger().info("[KafkaMessagingDatabase] Already connected; skipping re–initialization.");
            return;
        }
        try {
            // Producer properties.
            Properties producerProps = new Properties();
            producerProps.put("bootstrap.servers", config.node("bootstrapServers").getString("localhost:9092"));
            producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            producerProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

            producer = new KafkaProducer<>(producerProps);

            // Consumer properties.
            Properties consumerProps = new Properties();
            consumerProps.put("bootstrap.servers", config.node("bootstrapServers").getString("localhost:9092"));
            consumerProps.put("group.id", config.node("groupId").getString("defaultGroup"));
            consumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            consumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            consumerProps.put("auto.offset.reset", "earliest");

            consumer = new KafkaConsumer<>(consumerProps);

            int poolSize = config.node("pool_size").getInt(4);
            consumerExecutor = Executors.newFixedThreadPool(poolSize);

            dataAccess = new KafkaMessagingDataAccess(producer, consumer, consumerExecutor);
            connected = true;
            DataProviderApp.getLogger().info("[KafkaMessagingDatabase] Connected successfully to Kafka.");
        } catch (Exception e) {
            DataProviderApp.getLogger().error("[KafkaMessagingDatabase] Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void disconnect() {
        if (producer != null) {
            producer.close();
            DataProviderApp.getLogger().info("[KafkaMessagingDatabase] Producer closed.");
        }
        if (consumer != null) {
            consumer.close();
            DataProviderApp.getLogger().info("[KafkaMessagingDatabase] Consumer closed.");
        }
        if (consumerExecutor != null && !consumerExecutor.isShutdown()) {
            consumerExecutor.shutdown();
            DataProviderApp.getLogger().info("[KafkaMessagingDatabase] Consumer ExecutorService shut down.");
        }
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public MessagingDataAccess getDataAccess() {
        return dataAccess;
    }
}
