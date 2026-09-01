package nl.hauntedmc.dataprovider.database.coordination;

import java.util.Optional;

/** Result of an authoritative latest-owner-wins claim. */
public record LeaseClaim(FencedLease lease, Optional<String> previousOwner, long previousFencingToken) {

    public LeaseClaim {
        if (lease == null) {
            throw new IllegalArgumentException("Lease cannot be null.");
        }
        previousOwner = previousOwner == null ? Optional.empty() : previousOwner;
        if (previousFencingToken < 0) {
            throw new IllegalArgumentException("Previous fencing token cannot be negative.");
        }
    }
}
