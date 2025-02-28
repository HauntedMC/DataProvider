package nl.hauntedmc.dataprovider.database.messaging;

import nl.hauntedmc.dataprovider.database.DataAccess;
import java.util.concurrent.CompletableFuture;

/**
 * Defines messaging–related methods (e.g. event publishing and subscription)
 * in a vendor–neutral way.
 */
public interface MessagingDataAccess extends DataAccess {

    /**
     * Sends an event message to the specified destination.
     *
     * @param destination the destination (e.g. exchange, topic, or queue)
     * @param message the message payload
     * @return a CompletableFuture that completes when the message is sent
     */
    CompletableFuture<Void> sendEvent(String destination, String message);

    /**
     * Subscribes to events from the given destination.
     *
     * @param destination the destination (e.g. queue or topic)
     * @param listener the callback to receive messages
     * @return a CompletableFuture that completes when the subscription is established
     */
    CompletableFuture<Void> subscribe(String destination, MessageListener listener);

    /**
     * Unsubscribes from the given destination.
     *
     * @param destination the destination (e.g. queue or topic)
     * @return a CompletableFuture that completes when unsubscribed
     */
    CompletableFuture<Void> unsubscribe(String destination);
}
