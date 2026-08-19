package nl.hauntedmc.dataprovider.database.messaging.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagingConvenienceTest {

    @Test
    void subscriptionStateExposesLifecyclePredicates() {
        assertTrue(SubscriptionState.ACTIVE.isActive());
        assertFalse(SubscriptionState.RECONNECTING.isActive());
        assertTrue(SubscriptionState.CLOSED.isTerminal());
        assertTrue(SubscriptionState.FAILED.isTerminal());
        assertFalse(SubscriptionState.CLOSING.isTerminal());
    }

    @Test
    void subscriptionDelegatesLifecyclePredicatesToItsState() {
        Subscription active = new Subscription() {
            @Override public CompletableFuture<Void> unsubscribe() { return CompletableFuture.completedFuture(null); }
            @Override public SubscriptionState state() { return SubscriptionState.ACTIVE; }
        };
        Subscription failed = new Subscription() {
            @Override public CompletableFuture<Void> unsubscribe() { return CompletableFuture.completedFuture(null); }
            @Override public SubscriptionState state() { return SubscriptionState.FAILED; }
        };

        assertTrue(active.isActive());
        assertFalse(active.isTerminal());
        assertFalse(failed.isActive());
        assertTrue(failed.isTerminal());
    }

    @Test
    void abstractEventMessageExposesJavaTimeTimestamp() {
        TestMessage message = new TestMessage();

        assertEquals(Instant.ofEpochMilli(message.getTimestamp()), message.getTimestampInstant());
    }

    private static final class TestMessage extends AbstractEventMessage {
        private TestMessage() {
            super("test.message");
        }
    }
}
