package nl.hauntedmc.dataprovider.database.messaging.durable;

import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Acknowledged, at-least-once durable messaging for authoritative events.
 *
 * <p>Consumers must make effects idempotent with {@link DurableEvent#processingKey()} and only
 * acknowledge after the effect is committed. This yields exactly-once business effects despite
 * redelivery after process or Redis failure.</p>
 */
public interface DurableMessagingDataAccess {
    <T extends EventMessage> CompletableFuture<PublishedDurableEvent> publish(String stream, DurableEvent<T> event);

    <T extends EventMessage> DurableSubscription consume(
            String stream,
            String group,
            String consumer,
            Class<T> type,
            Consumer<DurableDelivery<T>> handler
    );

    List<DurableSubscriptionSnapshot> subscriptions();

    CompletableFuture<Void> shutdown();
}
