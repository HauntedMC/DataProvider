package nl.hauntedmc.dataprovider.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Immutable cached connection diagnostics. Reading a snapshot never performs I/O. */
public record ConnectionHealthSnapshot(
        LocalConnectionState localState,
        RemoteHealth remoteHealth,
        Instant checkedAt,
        ProviderLifecycleState lifecycleState,
        RuntimeHealth runtimeHealth,
        Circuit circuit,
        int consecutiveFailures,
        int consecutiveRecoveries,
        String lastFailureSummary,
        Instant degradedSince,
        int reconnectAttempts,
        Duration currentBackoff,
        Instant nextRecoveryAttempt
) {
    public enum LocalConnectionState { CONNECTED, DISCONNECTED }
    public enum RemoteHealth { UNKNOWN, HEALTHY, UNHEALTHY, ERROR }
    public enum RuntimeHealth { HEALTHY, DEGRADED, RECOVERING, UNAVAILABLE }
    public enum Circuit { CLOSED, OPEN, HALF_OPEN }

    /** Compatibility constructor retained for integrations compiled against earlier releases. */
    public ConnectionHealthSnapshot(LocalConnectionState localState, RemoteHealth remoteHealth, Instant checkedAt) {
        this(localState, remoteHealth, checkedAt, ProviderLifecycleState.READY,
                remoteHealth == RemoteHealth.HEALTHY ? RuntimeHealth.HEALTHY : RuntimeHealth.DEGRADED,
                Circuit.CLOSED, 0, 0, null, null, 0, Duration.ZERO, null);
    }

    public ConnectionHealthSnapshot {
        localState = Objects.requireNonNull(localState, "localState");
        remoteHealth = Objects.requireNonNull(remoteHealth, "remoteHealth");
        lifecycleState = Objects.requireNonNull(lifecycleState, "lifecycleState");
        runtimeHealth = Objects.requireNonNull(runtimeHealth, "runtimeHealth");
        circuit = Objects.requireNonNull(circuit, "circuit");
        if (consecutiveFailures < 0 || consecutiveRecoveries < 0 || reconnectAttempts < 0) {
            throw new IllegalArgumentException("Resilience counters cannot be negative.");
        }
        currentBackoff = Objects.requireNonNull(currentBackoff, "currentBackoff");
        if (currentBackoff.isNegative()) {
            throw new IllegalArgumentException("Current backoff cannot be negative.");
        }
    }

    public Duration degradedDuration() {
        return degradedDuration(Instant.now());
    }

    public Duration degradedDuration(Instant now) {
        Objects.requireNonNull(now, "now");
        if (degradedSince == null) {
            return Duration.ZERO;
        }
        Duration duration = Duration.between(degradedSince, now);
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    public static ConnectionHealthSnapshot unprobed(boolean locallyConnected) {
        return unprobed(locallyConnected, ProviderLifecycleState.READY);
    }

    public static ConnectionHealthSnapshot unprobed(boolean locallyConnected, ProviderLifecycleState lifecycleState) {
        return new ConnectionHealthSnapshot(
                locallyConnected ? LocalConnectionState.CONNECTED : LocalConnectionState.DISCONNECTED,
                RemoteHealth.UNKNOWN,
                null,
                lifecycleState,
                RuntimeHealth.DEGRADED,
                Circuit.CLOSED,
                0,
                0,
                null,
                null,
                0,
                Duration.ZERO,
                null
        );
    }

    /** Associates a shared physical-resource diagnostic with the caller's logical lifecycle. */
    public ConnectionHealthSnapshot withLifecycleState(ProviderLifecycleState lifecycleState) {
        return new ConnectionHealthSnapshot(
                localState, remoteHealth, checkedAt, lifecycleState, runtimeHealth, circuit,
                consecutiveFailures, consecutiveRecoveries, lastFailureSummary, degradedSince,
                reconnectAttempts, currentBackoff, nextRecoveryAttempt
        );
    }
}
