package nl.hauntedmc.dataprovider.core.resilience;

import org.spongepowered.configurate.CommentedConfigurationNode;
import java.time.Duration;
import java.util.Objects;

/** Validated settings for the core-owned backend health runtime. */
public record ResilienceRuntimeConfig(
        int workers,
        int queueCapacity,
        Duration healthInterval,
        Duration staleThreshold,
        int failureThreshold,
        int recoveryThreshold,
        Duration initialBackoff,
        Duration maxBackoff,
        double jitter,
        Duration shutdownGrace
) {
    public ResilienceRuntimeConfig {
        if (workers < 1 || queueCapacity < 1 || failureThreshold < 1 || recoveryThreshold < 1
                || healthInterval.isNegative() || healthInterval.isZero() || staleThreshold.isNegative()
                || initialBackoff.isNegative() || initialBackoff.isZero() || maxBackoff.compareTo(initialBackoff) < 0
                || !Double.isFinite(jitter) || jitter < 0 || jitter > 1 || shutdownGrace.isNegative()) {
            throw new IllegalArgumentException("Invalid resilience configuration.");
        }
    }
    public static ResilienceRuntimeConfig from(CommentedConfigurationNode root) {
        Objects.requireNonNull(root, "Configuration root cannot be null.");
        CommentedConfigurationNode resilience = root.node("resilience");
        return new ResilienceRuntimeConfig(
                bounded(resilience.node("workers").getInt(2), 1, 32, "workers"),
                bounded(resilience.node("queue_capacity").getInt(128), 1, 100_000, "queue_capacity"),
                Duration.ofMillis(bounded(
                        resilience.node("health_interval_ms").getLong(15_000),
                        100,
                        3_600_000,
                        "health_interval_ms")),
                Duration.ofMillis(bounded(
                        resilience.node("stale_threshold_ms").getLong(45_000),
                        0,
                        3_600_000,
                        "stale_threshold_ms")),
                bounded(resilience.node("failure_threshold").getInt(3), 1, 100, "failure_threshold"),
                bounded(resilience.node("recovery_threshold").getInt(1), 1, 100, "recovery_threshold"),
                Duration.ofMillis(bounded(
                        resilience.node("initial_backoff_ms").getLong(1_000),
                        50,
                        3_600_000,
                        "initial_backoff_ms")),
                Duration.ofMillis(bounded(
                        resilience.node("max_backoff_ms").getLong(30_000),
                        50,
                        3_600_000,
                        "max_backoff_ms")),
                bounded(resilience.node("jitter").getDouble(0.20), 0D, 1D, "jitter"),
                Duration.ofMillis(bounded(
                        resilience.node("shutdown_grace_ms").getLong(2_000),
                        0,
                        60_000,
                        "shutdown_grace_ms"))
        );
    }
    public static ResilienceRuntimeConfig defaults() {
        return new ResilienceRuntimeConfig(2, 128, Duration.ofSeconds(15), Duration.ofSeconds(45), 3, 1,
                Duration.ofSeconds(1), Duration.ofSeconds(30), .20, Duration.ofSeconds(2));
    }
    private static int bounded(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("resilience." + name + " is out of range.");
        }
        return value;
    }

    private static long bounded(long value, long minimum, long maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("resilience." + name + " is out of range.");
        }
        return value;
    }
    private static double bounded(double value, double min, double max, String name) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException("resilience." + name + " is out of range.");
        }
        return value;
    }
}
