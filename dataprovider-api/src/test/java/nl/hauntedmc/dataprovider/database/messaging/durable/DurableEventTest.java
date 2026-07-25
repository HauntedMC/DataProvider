package nl.hauntedmc.dataprovider.database.messaging.durable;

import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void rejectsUnsafeOrMissingIdempotencyKeys() {
        TestEvent payload = new TestEvent("vote.received");

        assertThrows(IllegalArgumentException.class, () -> new DurableEvent<>("event id", "key", payload));
        assertThrows(IllegalArgumentException.class, () -> new DurableEvent<>("event", "", payload));
    }

    private record TestEvent(String type) implements EventMessage {
        @Override public String getType() { return type; }
    }
}
