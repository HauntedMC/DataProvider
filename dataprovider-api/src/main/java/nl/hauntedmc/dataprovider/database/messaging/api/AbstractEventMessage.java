package nl.hauntedmc.dataprovider.database.messaging.api;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Base class with built‑in type + timestamp.
 */
public abstract class AbstractEventMessage implements EventMessage {
    private static final Pattern TYPE_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
    private static final AtomicLong LAST_TIMESTAMP = new AtomicLong();

    /*
     * These fields intentionally are not final: Gson populates message subclasses reflectively
     * without invoking this constructor, and future Java releases reject reflective final writes.
     * They remain encapsulated and have no mutators.
     */
    private String type;
    private long timestamp = monotonicEpochMillis();

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

    /** Creation timestamp as a Java time value. */
    public Instant getTimestampInstant() {
        return Instant.ofEpochMilli(timestamp);
    }

    private static long monotonicEpochMillis() {
        long wallClock = System.currentTimeMillis();
        return LAST_TIMESTAMP.accumulateAndGet(wallClock, Math::max);
    }
}
