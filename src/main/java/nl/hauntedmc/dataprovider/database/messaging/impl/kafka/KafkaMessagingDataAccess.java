package nl.hauntedmc.dataprovider.database.messaging.impl.kafka;

import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessageListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Implements MessagingDataAccess using Kafka.
 */
public class KafkaMessagingDataAccess implements MessagingDataAccess {

    private final KafkaProducer<String, String> producer;
    private final KafkaConsumer<String, String> consumer;
    private final ExecutorService consumerExecutor;

    public KafkaMessagingDataAccess(KafkaProducer<String, String> producer,
                                    KafkaConsumer<String, String> consumer,
                                    ExecutorService consumerExecutor) {
        this.producer = producer;
        this.consumer = consumer;
        this.consumerExecutor = consumerExecutor;
    }

    @Override
    public CompletableFuture<Void> sendEvent(String destination, String message) {
        return CompletableFuture.runAsync(() -> {
            try {
                // In Kafka, the destination is the topic.
                producer.send(new ProducerRecord<>(destination, message));
            } catch (Exception e) {
                throw new RuntimeException("Failed to send message via Kafka", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> subscribe(String destination, MessageListener listener) {
        return CompletableFuture.runAsync(() -> {
            // Subscribe to the topic.
            consumer.subscribe(Collections.singletonList(destination));
            // Run a consumer loop in the executor.
            // This loop is intended to run indefinitely until the thread is interrupted.
            consumerExecutor.submit(() -> {
                while (true) {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
                    for (ConsumerRecord<String, String> record : records) {
                        listener.onMessage(record.value());
                    }
                }
            });
        });
    }

    @Override
    public CompletableFuture<Void> unsubscribe(String destination) {
        return CompletableFuture.runAsync(consumer::unsubscribe);
    }
}
