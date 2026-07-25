package nl.hauntedmc.dataprovider.database.messaging.durable;

import java.util.Objects;

/** Result of durable publication, including the Redis-assigned stream entry ID. */
public record PublishedDurableEvent(String eventId, String streamEntryId, boolean newlyPublished) {
    public PublishedDurableEvent {
        Objects.requireNonNull(eventId, "eventId cannot be null");
        Objects.requireNonNull(streamEntryId, "streamEntryId cannot be null");
    }
}
