package nl.hauntedmc.dataprovider.core.resilience;

import nl.hauntedmc.dataprovider.core.ProviderLifecycleState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConnectionHealthSnapshotTest {

    @Test
    void unprobedSnapshotsReflectLocalConnectivityAndSafeDefaults() {
        ConnectionHealthSnapshot connected = ConnectionHealthSnapshot.unprobed(true);
        ConnectionHealthSnapshot disconnected = ConnectionHealthSnapshot.unprobed(
                false, ProviderLifecycleState.FAILED);

        assertEquals(ConnectionHealthSnapshot.LocalConnectionState.CONNECTED, connected.localState());
        assertEquals(ConnectionHealthSnapshot.LocalConnectionState.DISCONNECTED, disconnected.localState());
        assertEquals(ConnectionHealthSnapshot.RemoteHealth.UNKNOWN, connected.remoteHealth());
        assertEquals(ConnectionHealthSnapshot.RuntimeHealth.DEGRADED, connected.runtimeHealth());
        assertEquals(ConnectionHealthSnapshot.Circuit.CLOSED, connected.circuit());
        assertEquals(ProviderLifecycleState.READY, connected.lifecycleState());
        assertEquals(ProviderLifecycleState.FAILED, disconnected.lifecycleState());
        assertEquals(Duration.ZERO, connected.currentBackoff());
        assertEquals(Duration.ZERO, connected.degradedDuration(Instant.EPOCH));
        assertNull(connected.checkedAt());
        assertNull(connected.degradedSince());
        assertNull(connected.nextRecoveryAttempt());
    }

    @Test
    void degradedDurationUsesTheProvidedClockInstant() {
        Instant degradedSince = Instant.parse("2026-07-27T00:00:00Z");
        ConnectionHealthSnapshot snapshot = snapshot(degradedSince);

        assertEquals(Duration.ofMinutes(7), snapshot.degradedDuration(degradedSince.plusSeconds(420)));
    }

    @Test
    void degradedDurationClampsFutureStartTimesToZero() {
        Instant now = Instant.parse("2026-07-27T00:00:00Z");
        ConnectionHealthSnapshot snapshot = snapshot(now.plusSeconds(30));

        assertEquals(Duration.ZERO, snapshot.degradedDuration(now));
        assertThrows(NullPointerException.class, () -> snapshot.degradedDuration(null));
    }

    @Test
    void constructorRejectsNullMandatoryStatesAndBackoff() {
        assertThrows(NullPointerException.class, () -> new ConnectionHealthSnapshot(
                null,
                ConnectionHealthSnapshot.RemoteHealth.UNKNOWN,
                null,
                ProviderLifecycleState.READY,
                ConnectionHealthSnapshot.RuntimeHealth.HEALTHY,
                ConnectionHealthSnapshot.Circuit.CLOSED,
                0, 0, null, null, 0, Duration.ZERO, null
        ));
        assertThrows(NullPointerException.class, () -> new ConnectionHealthSnapshot(
                ConnectionHealthSnapshot.LocalConnectionState.CONNECTED,
                null,
                null,
                ProviderLifecycleState.READY,
                ConnectionHealthSnapshot.RuntimeHealth.HEALTHY,
                ConnectionHealthSnapshot.Circuit.CLOSED,
                0, 0, null, null, 0, Duration.ZERO, null
        ));
        assertThrows(NullPointerException.class, () -> new ConnectionHealthSnapshot(
                ConnectionHealthSnapshot.LocalConnectionState.CONNECTED,
                ConnectionHealthSnapshot.RemoteHealth.HEALTHY,
                null,
                null,
                ConnectionHealthSnapshot.RuntimeHealth.HEALTHY,
                ConnectionHealthSnapshot.Circuit.CLOSED,
                0, 0, null, null, 0, Duration.ZERO, null
        ));
        assertThrows(NullPointerException.class, () -> new ConnectionHealthSnapshot(
                ConnectionHealthSnapshot.LocalConnectionState.CONNECTED,
                ConnectionHealthSnapshot.RemoteHealth.HEALTHY,
                null,
                ProviderLifecycleState.READY,
                null,
                ConnectionHealthSnapshot.Circuit.CLOSED,
                0, 0, null, null, 0, Duration.ZERO, null
        ));
        assertThrows(NullPointerException.class, () -> new ConnectionHealthSnapshot(
                ConnectionHealthSnapshot.LocalConnectionState.CONNECTED,
                ConnectionHealthSnapshot.RemoteHealth.HEALTHY,
                null,
                ProviderLifecycleState.READY,
                ConnectionHealthSnapshot.RuntimeHealth.HEALTHY,
                null,
                0, 0, null, null, 0, Duration.ZERO, null
        ));
        assertThrows(NullPointerException.class, () -> new ConnectionHealthSnapshot(
                ConnectionHealthSnapshot.LocalConnectionState.CONNECTED,
                ConnectionHealthSnapshot.RemoteHealth.HEALTHY,
                null,
                ProviderLifecycleState.READY,
                ConnectionHealthSnapshot.RuntimeHealth.HEALTHY,
                ConnectionHealthSnapshot.Circuit.CLOSED,
                0, 0, null, null, 0, null, null
        ));
    }

    @Test
    void constructorRejectsNegativeCountersAndBackoff() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(-1, 0, 0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, -1, 0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 0, -1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(0, 0, 0, Duration.ofNanos(-1)));
    }

    @Test
    void lifecycleReplacementPreservesSharedPhysicalDiagnostics() {
        Instant now = Instant.parse("2026-07-27T00:00:00Z");
        ConnectionHealthSnapshot original = new ConnectionHealthSnapshot(
                ConnectionHealthSnapshot.LocalConnectionState.CONNECTED,
                ConnectionHealthSnapshot.RemoteHealth.UNHEALTHY,
                now,
                ProviderLifecycleState.READY,
                ConnectionHealthSnapshot.RuntimeHealth.RECOVERING,
                ConnectionHealthSnapshot.Circuit.HALF_OPEN,
                3,
                2,
                "timeout",
                now.minusSeconds(5),
                4,
                Duration.ofSeconds(2),
                now.plusSeconds(2)
        );

        ConnectionHealthSnapshot changed = original.withLifecycleState(ProviderLifecycleState.CLOSING);

        assertEquals(ProviderLifecycleState.READY, original.lifecycleState());
        assertEquals(ProviderLifecycleState.CLOSING, changed.lifecycleState());
        assertEquals(original.localState(), changed.localState());
        assertEquals(original.remoteHealth(), changed.remoteHealth());
        assertEquals(original.checkedAt(), changed.checkedAt());
        assertEquals(original.runtimeHealth(), changed.runtimeHealth());
        assertEquals(original.circuit(), changed.circuit());
        assertEquals(original.consecutiveFailures(), changed.consecutiveFailures());
        assertEquals(original.consecutiveRecoveries(), changed.consecutiveRecoveries());
        assertEquals(original.lastFailureSummary(), changed.lastFailureSummary());
        assertEquals(original.degradedSince(), changed.degradedSince());
        assertEquals(original.reconnectAttempts(), changed.reconnectAttempts());
        assertEquals(original.currentBackoff(), changed.currentBackoff());
        assertEquals(original.nextRecoveryAttempt(), changed.nextRecoveryAttempt());
        assertThrows(NullPointerException.class, () -> original.withLifecycleState(null));
    }

    private static ConnectionHealthSnapshot snapshot(Instant degradedSince) {
        return new ConnectionHealthSnapshot(
                ConnectionHealthSnapshot.LocalConnectionState.CONNECTED,
                ConnectionHealthSnapshot.RemoteHealth.UNHEALTHY,
                null,
                ProviderLifecycleState.FAILED,
                ConnectionHealthSnapshot.RuntimeHealth.DEGRADED,
                ConnectionHealthSnapshot.Circuit.OPEN,
                1, 0, "failure", degradedSince, 1, Duration.ofSeconds(1), null
        );
    }

    private static ConnectionHealthSnapshot snapshot(
            int failures,
            int recoveries,
            int attempts,
            Duration backoff
    ) {
        return new ConnectionHealthSnapshot(
                ConnectionHealthSnapshot.LocalConnectionState.CONNECTED,
                ConnectionHealthSnapshot.RemoteHealth.HEALTHY,
                null,
                ProviderLifecycleState.READY,
                ConnectionHealthSnapshot.RuntimeHealth.HEALTHY,
                ConnectionHealthSnapshot.Circuit.CLOSED,
                failures, recoveries, null, null, attempts, backoff, null
        );
    }
}
