package nl.hauntedmc.dataprovider.database.messaging.api;

/**
 * A JSON‑serialisable payload that can ride on any messaging backend.
 */
public interface EventMessage {
    /** Unique type key for this message. */
    String getType();
}
