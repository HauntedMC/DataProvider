package nl.hauntedmc.dataprovider.database.messaging.durable;

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
        String lastFailure
) { }
