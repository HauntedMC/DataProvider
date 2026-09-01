package nl.hauntedmc.dataprovider.database.messaging.durable;

import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TargetedDurableMessagingDataAccessTest {

    @Test
    void mapsEachRecipientToAnIsolatedStream() {
        RecordingDurableMessaging delegate = new RecordingDurableMessaging();
        TargetedDurableMessagingDataAccess targeted = delegate.targeted("network.delivery");
        DurableEvent<TestMessage> event = new DurableEvent<>("event-1", "effect-1", new TestMessage());

        targeted.publishTo("proxy-1.epoch-1", event).join();
        targeted.publishTo("proxy-2.epoch-2", event).join();
        targeted.consumeInbox("proxy-1.epoch-1", "consumer-1", "test", TestMessage.class, ignored -> { });

        assertEquals(List.of(
                "network.delivery.inbox.proxy-1.epoch-1",
                "network.delivery.inbox.proxy-2.epoch-2"
        ), delegate.publishedStreams);
        assertEquals("network.delivery.inbox.proxy-1.epoch-1", delegate.consumedStream);
        assertEquals("network.delivery.inbox", delegate.consumedGroup);
    }

    @Test
    void validatesTheWholeFanoutBeforePublishingAnything() {
        RecordingDurableMessaging delegate = new RecordingDurableMessaging();
        TargetedDurableMessagingDataAccess targeted = delegate.targeted("network.delivery");
        DurableEvent<TestMessage> event = new DurableEvent<>("event-1", "effect-1", new TestMessage());

        assertThrows(IllegalArgumentException.class,
                () -> targeted.fanOut(List.of("proxy-1", "proxy-1"), event));
        assertEquals(List.of(), delegate.publishedStreams);
    }

    private static final class TestMessage implements EventMessage {
        @Override public String getType() { return "test"; }
    }

    private static final class RecordingDurableMessaging implements DurableMessagingDataAccess {
        private final List<String> publishedStreams = new ArrayList<>();
        private String consumedStream;
        private String consumedGroup;

        @Override
        public <T extends EventMessage> CompletableFuture<PublishedDurableEvent> publish(
                String stream, DurableEvent<T> event
        ) {
            publishedStreams.add(stream);
            return CompletableFuture.completedFuture(new PublishedDurableEvent(event.eventId(), "1-0", true));
        }

        @Override
        public <T extends EventMessage> DurableSubscription consume(
                String stream, String group, String consumer, String messageType, Class<T> type,
                Consumer<DurableDelivery<T>> handler
        ) {
            consumedStream = stream;
            consumedGroup = group;
            return null;
        }

        @Override public List<DurableSubscriptionSnapshot> subscriptions() { return List.of(); }
        @Override public CompletableFuture<Void> shutdown() { return CompletableFuture.completedFuture(null); }
    }
}
