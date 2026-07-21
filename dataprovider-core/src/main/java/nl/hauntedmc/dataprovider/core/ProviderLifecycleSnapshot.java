package nl.hauntedmc.dataprovider.core;

import java.time.Instant;

/** Internal diagnostic snapshot for a provider slot. */
record ProviderLifecycleSnapshot(
        ProviderLifecycleState state,
        Throwable failure,
        Instant changedAt
) {
}
