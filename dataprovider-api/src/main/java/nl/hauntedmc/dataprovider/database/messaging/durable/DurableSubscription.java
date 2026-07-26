package nl.hauntedmc.dataprovider.database.messaging.durable;

import java.util.concurrent.CompletableFuture;

/** A named consumer running in a durable consumer group. */
public interface DurableSubscription extends AutoCloseable {
    String id();
    DurableSubscriptionSnapshot snapshot();
    CompletableFuture<Void> closeAsync();
    CompletableFuture<Void> completion();

    @Override
    default void close() {
        closeAsync().join();
    }
}
