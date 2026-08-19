package nl.hauntedmc.dataprovider.database.messaging.durable;

import nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurableMessagingConvenienceTest {

    @Test
    void durableSubscriptionExposesLifecyclePredicates() {
        DurableSubscription subscription = new DurableSubscription() {
            @Override public String id() { return "consumer"; }
            @Override public DurableSubscriptionSnapshot snapshot() {
                return new DurableSubscriptionSnapshot(
                        "consumer", "events", "group", "node", true,
                        0, 0, 0, 0, 0, 0, null,
                        SubscriptionState.ACTIVE, 0, null, Duration.ZERO, Duration.ZERO
                );
            }
            @Override public CompletableFuture<Void> closeAsync() { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<Void> completion() { return CompletableFuture.completedFuture(null); }
        };

        assertTrue(subscription.isActive());
        assertFalse(subscription.isTerminal());
    }

    @Test
    void publishedDurableEventMakesDeduplicationOutcomeExplicit() {
        assertFalse(new PublishedDurableEvent("event-1", "1-0", true).wasDeduplicated());
        assertTrue(new PublishedDurableEvent("event-1", "1-0", false).wasDeduplicated());
    }
}
