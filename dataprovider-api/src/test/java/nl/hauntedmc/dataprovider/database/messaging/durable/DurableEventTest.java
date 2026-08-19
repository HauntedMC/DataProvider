package nl.hauntedmc.dataprovider.database.messaging.durable;

import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableEventTest {

    @Test
    void createSuppliesStableValidEventAndProcessingKeys() {
        DurableEvent<TestEvent> event = DurableEvent.create(new TestEvent("vote.received"));

        assertEquals(event.eventId(), event.processingKey());
        assertTrue(event.eventId().matches("[A-Za-z0-9_.:-]{1,200}"));
    }

    @Test
    void createWithBusinessKeyGeneratesIndependentProducerEventId() {
        TestEvent payload = new TestEvent("vote.received");
        DurableEvent<TestEvent> event = DurableEvent.create("vote-123", payload);

        assertEquals("vote-123", event.processingKey());
        assertNotEquals(event.processingKey(), event.eventId());
        assertSame(payload, event.payload());
    }

    @Test
    void deliveryConveniencesExposeEnvelopeValues() {
        TestEvent payload = new TestEvent("vote.received");
        DurableEvent<TestEvent> event = new DurableEvent<>("event-1", "vote-123", payload);
        DurableDelivery<TestEvent> delivery = new DurableDelivery<>() {
            @Override public String stream() { return "votes"; }
            @Override public String group() { return "server"; }
            @Override public String consumer() { return "survival"; }
            @Override public String streamEntryId() { return "1-0"; }
            @Override public DurableEvent<TestEvent> event() { return event; }
            @Override public int attempt() { return 2; }
            @Override public CompletableFuture<Void> acknowledge() { return CompletableFuture.completedFuture(null); }
        };

        assertSame(payload, delivery.payload());
        assertEquals("event-1", delivery.eventId());
        assertEquals("vote-123", delivery.processingKey());
        assertTrue(delivery.isRedelivery());
    }

    @Test
    void rejectsUnsafeOrMissingIdempotencyKeys() {
        TestEvent payload = new TestEvent("vote.received");

        assertThrows(IllegalArgumentException.class, () -> new DurableEvent<>("event id", "key", payload));
        assertThrows(IllegalArgumentException.class, () -> new DurableEvent<>("event", "", payload));
        assertThrows(IllegalArgumentException.class, () -> DurableEvent.create("business key", payload));
    }

    @Test
    void durableSubscriptionIsNotAutoCloseable() {
        assertTrue(!AutoCloseable.class.isAssignableFrom(DurableSubscription.class));
    }

    private record TestEvent(String type) implements EventMessage {
        @Override public String getType() { return type; }
    }
}
