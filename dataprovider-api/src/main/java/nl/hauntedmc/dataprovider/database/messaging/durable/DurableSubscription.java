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
        // A handler may close its own subscription. The consumer cannot complete until the
        // handler returns, so callers that need to wait must use completion() outside the handler.
        closeAsync();
    }
}
