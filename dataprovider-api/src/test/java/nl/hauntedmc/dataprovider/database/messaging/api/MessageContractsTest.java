package nl.hauntedmc.dataprovider.database.messaging.api;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void subscriptionClosePropagatesCleanupFailure() {
        Subscription subscription = () ->
                CompletableFuture.failedFuture(new IllegalStateException("unsubscribe failed"));

        assertThrows(CompletionException.class, subscription::close);
    }

    private static final class TestMessage extends AbstractEventMessage {

        private TestMessage(String type) {
            super(type);
        }
    }
}
