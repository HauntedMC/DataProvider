import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableEvent;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableMessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableSubscription;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A vote/purchase/punishment style integration. The database transaction must enforce a unique
 * processing key; only then is acknowledgement safe.
 */
public final class RedisDurableMessagingExample {
    private static final String VOTE_STREAM = "haunted.vote";
    private static final String GROUP = "vote-appliers";
    private static final String CONSUMER = "survival-1";
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10L;

    private RedisDurableMessagingExample() {
    }

    DurableSubscription install(MessagingDatabaseProvider provider) {
        DurableMessagingDataAccess durable = provider.getDurableDataAccess();
        return durable.consume(VOTE_STREAM, GROUP, CONSUMER, "vote_received", VoteReceived.class, delivery -> {
            // In one database transaction: INSERT processing_key with a UNIQUE constraint, then apply the vote.
            // If the key already exists, the transaction makes this a no-op.
            applyVoteIdempotently(delivery.processingKey(), delivery.payload());
            delivery.acknowledge().join(); // Only after the transaction commits.
        });
    }

    void uninstall(DurableSubscription subscription) {
        awaitShutdown(subscription.closeAsync());
    }

    void recordVote(DurableMessagingDataAccess durable, VoteReceived vote) {
        DurableEvent<VoteReceived> event = DurableEvent.create("vote:" + vote.voteId(), vote);
        durable.publish(VOTE_STREAM, event);
    }

    private void applyVoteIdempotently(String processingKey, VoteReceived vote) {
        // Application-specific transactional persistence.
    }

    private static void awaitShutdown(CompletableFuture<Void> shutdown) {
        try {
            shutdown.get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping the durable consumer.", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Durable consumer did not stop cleanly.", failure);
        }
    }

    record VoteReceived(String type, String voteId, String playerId) implements EventMessage {
        @Override public String getType() { return type; }
    }
}
