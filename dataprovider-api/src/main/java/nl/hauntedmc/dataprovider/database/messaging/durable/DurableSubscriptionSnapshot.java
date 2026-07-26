package nl.hauntedmc.dataprovider.database.messaging.durable;

import nl.hauntedmc.dataprovider.database.messaging.api.SubscriptionState;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Consumer-group health and backlog diagnostic values. */
public record DurableSubscriptionSnapshot(
        String id,
        String stream,
        String group,
        String consumer,
        boolean active,
        long pendingCount,
        long lag,
        long deliveredCount,
        long acknowledgedCount,
        long reclaimedCount,
        long deadLetteredCount,
        String lastFailure,
        SubscriptionState state,
        long reconnectCount,
        Instant lastFailureAt,
        Duration currentDowntime,
        Duration totalDowntime
) {
    /** Retained for source compatibility with the pre-lifecycle diagnostic shape. */
    public DurableSubscriptionSnapshot(
            String id, String stream, String group, String consumer, boolean active,
            long pendingCount, long lag, long deliveredCount, long acknowledgedCount,
            long reclaimedCount, long deadLetteredCount, String lastFailure
    ) {
        this(id, stream, group, consumer, active, pendingCount, lag, deliveredCount, acknowledgedCount,
                reclaimedCount, deadLetteredCount, lastFailure,
                active ? SubscriptionState.ACTIVE : SubscriptionState.CLOSED,
                0L, null, Duration.ZERO, Duration.ZERO);
    }

    public DurableSubscriptionSnapshot {
        id = Objects.requireNonNull(id, "Id cannot be null.");
        stream = Objects.requireNonNull(stream, "Stream cannot be null.");
        group = Objects.requireNonNull(group, "Group cannot be null.");
        consumer = Objects.requireNonNull(consumer, "Consumer cannot be null.");
        state = Objects.requireNonNull(state, "State cannot be null.");
        currentDowntime = Objects.requireNonNull(currentDowntime, "Current downtime cannot be null.");
        totalDowntime = Objects.requireNonNull(totalDowntime, "Total downtime cannot be null.");
        if (reconnectCount < 0L) {
            throw new IllegalArgumentException("Reconnect count cannot be negative.");
        }
    }
}
