package nl.hauntedmc.dataprovider.database.messaging.api;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Stable logical handle returned by subscribe(). Physical listener connections may be replaced while this
 * handle, its handler registration and its diagnostics remain valid.
 */
@FunctionalInterface
public interface Subscription extends AutoCloseable {
    /** Stops this subscription. */
    CompletableFuture<Void> unsubscribe();

    /** Stable logical identity for diagnostics. Empty only for legacy implementations. */
    default String id() {
        return "";
    }

    /** Current logical lifecycle state. */
    default SubscriptionState state() {
        return SubscriptionState.CLOSED;
    }

    /** Current immutable diagnostics. */
    default SubscriptionSnapshot snapshot() {
        return new SubscriptionSnapshot(
                id(), "", "", state(), 0, 0, null, null,
                Duration.ZERO, Duration.ZERO, false
        );
    }

    /**
     * Completes normally after an explicit close, or exceptionally when automatic recovery reaches a
     * terminal failure. Transient listener failures do not complete this future.
     */
    default CompletableFuture<Void> completion() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    default void close() {
        unsubscribe().join();
    }
}
