package nl.hauntedmc.dataprovider.database.messaging.durable;

import nl.hauntedmc.dataprovider.database.messaging.api.EventMessage;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An immutable, idempotent event envelope for durable messaging.
 *
 * <p>{@code eventId} identifies a producer operation and is used to make publishing retry-safe.
 * {@code processingKey} identifies the business effect. Persist that key in the same transaction as
 * the effect, under a unique constraint, before acknowledging the delivery.</p>
 */
public record DurableEvent<T extends EventMessage>(String eventId, String processingKey, T payload) {
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,200}");

    public DurableEvent {
        requireKey(eventId, "eventId");
        requireKey(processingKey, "processingKey");
        Objects.requireNonNull(payload, "payload cannot be null");
    }

    /** Creates an event whose event and processing IDs are both stable UUIDs. */
    public static <T extends EventMessage> DurableEvent<T> create(T payload) {
        String id = UUID.randomUUID().toString();
        return new DurableEvent<>(id, id, payload);
    }

    /**
     * Creates an event with a generated producer ID and caller-owned business processing key.
     * Create the envelope once and reuse that same instance for uncertain publish retries.
     */
    public static <T extends EventMessage> DurableEvent<T> create(String processingKey, T payload) {
        return new DurableEvent<>(UUID.randomUUID().toString(), processingKey, payload);
    }

    private static void requireKey(String value, String name) {
        if (value == null || !KEY_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + KEY_PATTERN.pattern());
        }
    }
}
