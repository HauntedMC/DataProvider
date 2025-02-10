package nl.hauntedmc.dataprovider.database.messaging.impl.kafka;

import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.logging.DPLogger;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * KafkaMessagingDatabase implements MessagingDatabaseProvider using Kafka.
 */
public class KafkaMessagingDatabase implements MessagingDatabaseProvider {

    private final ConfigurationSection config;
    private KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> consumer;
    private ExecutorService consumerExecutor;
    private KafkaMessagingDataAccess dataAccess;
    private boolean connected;

    public KafkaMessagingDatabase(ConfigurationSection config) {
        this.config = config;
    }

    @Override
    public void connect() {
        if (connected) {
            DPLogger.info("[KafkaMessagingDatabase] Already connected; skipping re–initialization.");
            return;
        }
        try {
            // Producer properties.
            Properties producerProps = new Properties();
            producerProps.put("bootstrap.servers", config.getString("bootstrapServers", "localhost:9092"));
            producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            producerProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

            producer = new KafkaProducer<>(producerProps);

            // Consumer properties.
            Properties consumerProps = new Properties();
            consumerProps.put("bootstrap.servers", config.getString("bootstrapServers", "localhost:9092"));
            consumerProps.put("group.id", config.getString("groupId", "defaultGroup"));
            consumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            consumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            consumerProps.put("auto.offset.reset", "earliest");

            consumer = new KafkaConsumer<>(consumerProps);

            int poolSize = config.getInt("pool_size", 4);
            consumerExecutor = Executors.newFixedThreadPool(poolSize);

            dataAccess = new KafkaMessagingDataAccess(producer, consumer, consumerExecutor);
            connected = true;
            DPLogger.info("[KafkaMessagingDatabase] Connected successfully to Kafka.");
        } catch (Exception e) {
            DPLogger.error("[KafkaMessagingDatabase] Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void disconnect() {
        if (producer != null) {
            producer.close();
            DPLogger.info("[KafkaMessagingDatabase] Producer closed.");
        }
        if (consumer != null) {
            consumer.close();
            DPLogger.info("[KafkaMessagingDatabase] Consumer closed.");
        }
        if (consumerExecutor != null && !consumerExecutor.isShutdown()) {
            consumerExecutor.shutdown();
            DPLogger.info("[KafkaMessagingDatabase] Consumer ExecutorService shut down.");
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
