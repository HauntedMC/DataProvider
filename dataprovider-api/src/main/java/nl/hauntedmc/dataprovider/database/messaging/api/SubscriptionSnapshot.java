package nl.hauntedmc.dataprovider.database.messaging.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Immutable diagnostics for one logical handler subscription. */
public record SubscriptionSnapshot(
        String logicalId,
        String destination,
        String messageType,
        SubscriptionState state,
        long reconnectCount,
        long generation,
        Instant lastFailureAt,
        String lastFailure,
        Duration currentDowntime,
        Duration totalDowntime,
        boolean activeListener
) {
    public SubscriptionSnapshot {
        logicalId = Objects.requireNonNull(logicalId, "Logical id cannot be null.");
        destination = Objects.requireNonNull(destination, "Destination cannot be null.");
        messageType = Objects.requireNonNull(messageType, "Message type cannot be null.");
        state = Objects.requireNonNull(state, "State cannot be null.");
        currentDowntime = Objects.requireNonNull(currentDowntime, "Current downtime cannot be null.");
        totalDowntime = Objects.requireNonNull(totalDowntime, "Total downtime cannot be null.");
        if (reconnectCount < 0) {
            throw new IllegalArgumentException("Reconnect count cannot be negative.");
        }
        if (generation < 0) {
            throw new IllegalArgumentException("Generation cannot be negative.");
        }
    }
}
