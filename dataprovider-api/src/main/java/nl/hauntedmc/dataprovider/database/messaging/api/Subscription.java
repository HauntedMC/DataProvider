package nl.hauntedmc.dataprovider.database.messaging.api;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Stable logical handle returned by subscribe(). Physical listener connections may be replaced while this
 * handle, its handler registration and its diagnostics remain valid.
 */
@FunctionalInterface
public interface Subscription {
    /**
     * Starts shutdown and completes when this subscription can no longer receive callbacks.
     *
     * <p>This operation is deliberately asynchronous: it is safe to invoke from a handler, where
     * waiting for termination can deadlock. Callers that need a termination barrier must await the
     * returned future with their own application timeout.</p>
     */
    CompletableFuture<Void> unsubscribe();

    /** Stable logical identity for diagnostics. Empty only for legacy implementations. */
    default String id() {
        return "";
    }

    /** Current logical lifecycle state. */
    default SubscriptionState state() {
        return SubscriptionState.CLOSED;
    }

    /** Whether this subscription is currently delivering messages. */
    default boolean isActive() {
        return state().isActive();
    }

    /** Whether this subscription has reached a terminal closed or failed state. */
    default boolean isTerminal() {
        return state().isTerminal();
    }

    /** Current immutable diagnostics. */
    default SubscriptionSnapshot snapshot() {
        return new SubscriptionSnapshot(
                id(), "", "", state(), 0, 0, null, null,
                Duration.ZERO, Duration.ZERO, false
        );
    }

    /**
     * Completes normally after an explicit unsubscribe, or exceptionally when automatic recovery reaches a
     * terminal failure. Transient listener failures do not complete this future.
     */
    default CompletableFuture<Void> completion() {
        return CompletableFuture.completedFuture(null);
    }

}
