package nl.hauntedmc.dataprovider.database.messaging.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageContractsTest {

    @Test
    void abstractEventMessageStoresTypeAndTimestamp() {
        long before = System.currentTimeMillis();
        TestMessage message = new TestMessage("event.test");
        long after = System.currentTimeMillis();

        assertEquals("event.test", message.getType());
        assertTrue(message.getTimestamp() >= before);
        assertTrue(message.getTimestamp() <= after);
    }

    @Test
    void eventTimestampsDoNotMoveBackwardsWithinTheProcess() {
        long previous = Long.MIN_VALUE;
        for (int index = 0; index < 1_000; index++) {
            long timestamp = new TestMessage("event.test").getTimestamp();
            assertTrue(timestamp >= previous);
            previous = timestamp;
        }
    }

    @Test
    void abstractEventMessageRejectsUnsafeTypes() {
        assertThrows(IllegalArgumentException.class, () -> new TestMessage(null));
        assertThrows(IllegalArgumentException.class, () -> new TestMessage(""));
        assertThrows(IllegalArgumentException.class, () -> new TestMessage("event\nforged"));
        assertThrows(IllegalArgumentException.class, () -> new TestMessage("x".repeat(129)));
    }

    @Test
    void subscriptionCloseDelegatesToUnsubscribe() {
        AtomicBoolean unsubscribed = new AtomicBoolean(false);
        Subscription subscription = () -> {
            unsubscribed.set(true);
            return CompletableFuture.completedFuture(null);
        };

        subscription.close();

        assertTrue(unsubscribed.get());
    }

    @Test
    void subscriptionCloseDoesNotWaitForCleanup() {
        Subscription subscription = () ->
                CompletableFuture.failedFuture(new IllegalStateException("unsubscribe failed"));

        subscription.close();
    }

    private static final class TestMessage extends AbstractEventMessage {

        private TestMessage(String type) {
            super(type);
        }
    }
}
