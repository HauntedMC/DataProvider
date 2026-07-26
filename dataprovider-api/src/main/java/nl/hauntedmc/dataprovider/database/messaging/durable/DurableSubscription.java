package nl.hauntedmc.dataprovider.database.messaging.durable;

import nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionState;

import java.util.concurrent.CompletableFuture;

/** A named consumer running in a durable consumer group. */
public interface DurableSubscription {
    String id();
    DurableSubscriptionSnapshot snapshot();

    /** Current logical lifecycle state. */
    default SubscriptionState state() {
        return snapshot().state();
    }

    /**
     * Starts shutdown and completes when this consumer has stopped.
     *
     * <p>This operation is safe to invoke from a delivery handler. A handler must not wait for the
     * returned future because the consumer cannot finish until that handler returns.</p>
     */
    CompletableFuture<Void> closeAsync();
    CompletableFuture<Void> completion();
}
