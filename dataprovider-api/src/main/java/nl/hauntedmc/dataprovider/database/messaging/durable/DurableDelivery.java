package nl.hauntedmc.dataprovider.database.messaging.durable;

import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;

import java.util.concurrent.CompletableFuture;

/**
 * One at-least-once delivery from a consumer group.
 *
 * <p>Call {@link #acknowledge()} only after the business operation and durable processing-key
 * record have committed. An unacknowledged delivery is reclaimed after the configured idle period.</p>
 */
public interface DurableDelivery<T extends EventMessage> {
    String stream();
    String group();
    String consumer();
    String streamEntryId();
    DurableEvent<T> event();
    int attempt();
    CompletableFuture<Void> acknowledge();
}
