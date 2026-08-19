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

    /** Whether this durable consumer is currently processing deliveries. */
    default boolean isActive() {
        return state().isActive();
    }

    /** Whether this durable consumer has reached a terminal closed or failed state. */
    default boolean isTerminal() {
        return state().isTerminal();
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
