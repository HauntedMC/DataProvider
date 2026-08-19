package nl.hauntedmc.dataprovider.database.messaging.api;

/** Lifecycle state of a durable logical messaging subscription. */
public enum SubscriptionState {
    CONNECTING,
    ACTIVE,
    RECONNECTING,
    CLOSING,
    CLOSED,
    FAILED;

    /** Whether the logical subscription is currently delivering messages. */
    public boolean isActive() {
        return this == ACTIVE;
    }

    /** Whether no further automatic lifecycle transition is expected. */
    public boolean isTerminal() {
        return this == CLOSED || this == FAILED;
    }
}
