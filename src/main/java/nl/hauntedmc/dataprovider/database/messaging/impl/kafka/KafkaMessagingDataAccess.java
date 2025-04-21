package nl.hauntedmc.dataprovider.database.messaging.impl.kafka;

import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Implements MessagingDataAccess using Kafka.
 */
public class KafkaMessagingDataAccess implements MessagingDataAccess {

    private static <T> CompletableFuture<T> notImpl() {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Kafka support not implemented")
        );
    }

    @Override
    public <T extends EventMessage> CompletableFuture<Void> publish(
            String destination, T message
    ) {
        return notImpl();
    }

    @Override
    public <T extends EventMessage> Subscription subscribe(
            String destination, Class<T> type, Consumer<T> handler
    ) {
        throw new UnsupportedOperationException("Kafka support not implemented");
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return notImpl();
    }
}
