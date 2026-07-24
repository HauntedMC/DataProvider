package nl.hauntedmc.dataprovider.core.resilience;

import nl.hauntedmc.dataprovider.core.ManagedDatabaseProvider;

/**
 * Exposes the physical resource that owns health probing and recovery for a stable logical
 * provider facade. Multiple logical registrations may intentionally share this target.
 */
public interface ResilienceTargetAware {
    ManagedDatabaseProvider resilienceTarget();
}
