package nl.hauntedmc.dataprovider.core.concurrent;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;

/** Rejection with a stable reason suitable for diagnostics and metrics. */
public final class ExecutionRejectedException extends RejectedExecutionException {

    private final Reason reason;

    public ExecutionRejectedException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "Reason cannot be null.");
    }

    public ExecutionRejectedException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "Reason cannot be null.");
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        RUNTIME_SHUTTING_DOWN,
        SCOPE_CLOSED,
        LANE_QUEUE_FULL,
        PLUGIN_QUEUE_LIMIT,
        CONNECTION_QUEUE_LIMIT,
        SUBSCRIPTION_LIMIT
    }
}
