package nl.hauntedmc.dataprovider.core;

import java.time.Instant;

/** Cached result of an asynchronous remote database health probe. */
public record ConnectionHealthSnapshot(LocalConnectionState localState, RemoteHealth remoteHealth, Instant checkedAt) {

    public enum LocalConnectionState {
        CONNECTED,
        DISCONNECTED
    }

    public enum RemoteHealth {
        UNKNOWN,
        HEALTHY,
        UNHEALTHY,
        ERROR
    }

    public static ConnectionHealthSnapshot unprobed(boolean locallyConnected) {
        return new ConnectionHealthSnapshot(
                locallyConnected ? LocalConnectionState.CONNECTED : LocalConnectionState.DISCONNECTED,
                RemoteHealth.UNKNOWN,
                null
        );
    }
}
