package nl.hauntedmc.dataprovider.database.messaging.api;


/**
 * Base class with built‑in type + timestamp.
 */
public abstract class AbstractEventMessage implements EventMessage {
    private final String type;
    private final long timestamp = System.currentTimeMillis();

    protected AbstractEventMessage(String type) {
        this.type = type;
    }

    @Override public String getType() {
        return type;
    }

    /** Epoch millis when this object was created. */
    public long getTimestamp() {
        return timestamp;
    }
}