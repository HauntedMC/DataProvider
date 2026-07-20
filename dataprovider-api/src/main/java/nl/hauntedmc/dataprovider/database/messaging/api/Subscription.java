package nl.hauntedmc.dataprovider.database.messaging.api;

import java.util.concurrent.CompletableFuture;

/**
 * Opaque handle returned by subscribe(); call unsubscribe() to stop.
 */
public interface Subscription extends AutoCloseable {
    /** Stops this subscription. */
    CompletableFuture<Void> unsubscribe();

    @Override default void close() {
        unsubscribe();
    }
}
