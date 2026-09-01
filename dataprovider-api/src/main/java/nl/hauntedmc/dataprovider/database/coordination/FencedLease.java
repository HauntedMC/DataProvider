package nl.hauntedmc.dataprovider.database.coordination;

import java.time.Instant;
import java.util.Objects;

/** A renewable distributed lease whose monotonically increasing token fences former owners. */
public record FencedLease(String resource, String owner, long fencingToken, Instant expiresAt) {

    public FencedLease {
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("Resource cannot be null or blank.");
        }
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("Owner cannot be null or blank.");
        }
        if (fencingToken < 1) {
            throw new IllegalArgumentException("Fencing token must be positive.");
        }
        Objects.requireNonNull(expiresAt, "Expiry cannot be null.");
    }
}
