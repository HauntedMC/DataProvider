package nl.hauntedmc.dataprovider.database.messaging.api;

import java.util.regex.Pattern;

/**
 * Base class with built‑in type + timestamp.
 */
public abstract class AbstractEventMessage implements EventMessage {
    private static final Pattern TYPE_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");

    private final String type;
    private final long timestamp = System.currentTimeMillis();

    protected AbstractEventMessage(String type) {
        if (type == null || !TYPE_PATTERN.matcher(type).matches()) {
            throw new IllegalArgumentException("Message type contains unsupported characters or has an invalid length.");
        }
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
