package nl.hauntedmc.dataprovider.database.messaging;

import nl.hauntedmc.dataprovider.database.DataAccess;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.api.Subscription;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Defines messaging–related methods (e.g. event publishing and subscription)
 * in a vendor–neutral way.
 */
public interface MessagingDataAccess extends DataAccess {

    /**
     * Publish a typed message to the given destination (channel/topic).
     */
    <T extends EventMessage> CompletableFuture<Void> publish(
            String destination, T message
    );

    /**
     * Subscribe to messages of a given type on the destination.
     * Returns a handle for unsubscription.
     */
    <T extends EventMessage> Subscription subscribe(
            String destination,
            Class<T> type,
            Consumer<T> handler
    );

    /** Gracefully shut down all background resources. */
    CompletableFuture<Void> shutdown();
}
