package nl.hauntedmc.dataprovider.database.messaging.api;

/** Lifecycle state of a durable logical messaging subscription. */
public enum SubscriptionState {
    CONNECTING,
    ACTIVE,
    RECONNECTING,
    CLOSING,
    CLOSED,
    FAILED
}
