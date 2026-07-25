import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableEvent;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableMessagingDataAccess;

/**
 * A vote/purchase/punishment style integration. The database transaction must enforce a unique
 * processing key; only then is acknowledgement safe.
 */
public final class RedisDurableMessagingExample {
    private static final String VOTE_STREAM = "haunted.vote";
    private static final String GROUP = "vote-appliers";
    private static final String CONSUMER = "survival-1";

    private RedisDurableMessagingExample() {
    }

    void install(MessagingDatabaseProvider provider) {
        DurableMessagingDataAccess durable = provider.getDurableDataAccess();
        durable.consume(VOTE_STREAM, GROUP, CONSUMER, VoteReceived.class, delivery -> {
            // In one database transaction: INSERT processing_key with a UNIQUE constraint, then apply the vote.
            // If the key already exists, the transaction makes this a no-op.
            applyVoteIdempotently(delivery.event().processingKey(), delivery.event().payload());
            delivery.acknowledge().join(); // Only after the transaction commits.
        });
    }

    void recordVote(DurableMessagingDataAccess durable, VoteReceived vote) {
        DurableEvent<VoteReceived> event = new DurableEvent<>(vote.voteId(), "vote:" + vote.voteId(), vote);
        durable.publish(VOTE_STREAM, event);
    }

    private void applyVoteIdempotently(String processingKey, VoteReceived vote) {
        // Application-specific transactional persistence.
    }

    record VoteReceived(String type, String voteId, String playerId) implements EventMessage {
        @Override public String getType() { return type; }
    }
}
