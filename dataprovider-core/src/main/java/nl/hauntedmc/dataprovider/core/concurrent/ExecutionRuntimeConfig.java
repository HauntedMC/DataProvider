package nl.hauntedmc.dataprovider.core.concurrent;

import org.spongepowered.configurate.CommentedConfigurationNode;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable bounded execution settings loaded from config.yml. */
public record ExecutionRuntimeConfig(
        Map<ExecutionLane, LaneConfig> lanes,
        Duration scopeShutdownGrace,
        Duration runtimeShutdownGrace,
        int messagingGlobalSubscriptions,
        int messagingPerPluginSubscriptions,
        int messagingPerConnectionSubscriptions
) {
    public ExecutionRuntimeConfig {
        lanes = Map.copyOf(Objects.requireNonNull(lanes, "Lane configurations cannot be null."));
        for (ExecutionLane lane : ExecutionLane.values()) {
            if (!lanes.containsKey(lane)) {
                throw new IllegalArgumentException("Missing execution lane configuration for " + lane);
            }
        }
        Objects.requireNonNull(scopeShutdownGrace, "Scope shutdown grace cannot be null.");
        Objects.requireNonNull(runtimeShutdownGrace, "Runtime shutdown grace cannot be null.");
        if (scopeShutdownGrace.isNegative() || runtimeShutdownGrace.isNegative()) {
            throw new IllegalArgumentException("Execution shutdown grace durations cannot be negative.");
        }
        if (messagingGlobalSubscriptions < 1
                || messagingPerPluginSubscriptions < 1
                || messagingPerConnectionSubscriptions < 1) {
            throw new IllegalArgumentException("Messaging subscription limits must be positive.");
        }
        if (messagingPerPluginSubscriptions > messagingGlobalSubscriptions
                || messagingPerConnectionSubscriptions > messagingPerPluginSubscriptions) {
            throw new IllegalArgumentException(
                    "Messaging subscription limits must satisfy global >= per-plugin >= per-connection."
            );
        }
    }

    public static ExecutionRuntimeConfig from(CommentedConfigurationNode root) {
        Objects.requireNonNull(root, "Configuration root cannot be null.");
        EnumMap<ExecutionLane, LaneConfig> configs = new EnumMap<>(ExecutionLane.class);
        configs.put(ExecutionLane.RELATIONAL, lane(root, "relational", 8, 2_048, 4, 512, 2, 128));
        configs.put(ExecutionLane.DOCUMENT, lane(root, "document", 8, 2_048, 4, 512, 2, 128));
        configs.put(ExecutionLane.REDIS, lane(root, "redis", 8, 2_048, 4, 512, 2, 128));
        configs.put(ExecutionLane.MESSAGING, lane(root, "messaging", 8, 4_096, 4, 1_024, 2, 256));
        long scopeGraceMs = bounded(root.node("execution", "scope_shutdown_grace_ms").getLong(2_000L),
                0, 60_000, "execution.scope_shutdown_grace_ms");
        long runtimeGraceMs = bounded(root.node("execution", "runtime_shutdown_grace_ms").getLong(5_000L),
                0, 120_000, "execution.runtime_shutdown_grace_ms");
        CommentedConfigurationNode subscriptions = root.node("execution", "messaging_subscriptions");
        int globalSubscriptions = bounded(subscriptions.node("global").getInt(256),
                1, 100_000, "execution.messaging_subscriptions.global");
        int perPluginSubscriptions = bounded(subscriptions.node("per_plugin").getInt(64),
                1, globalSubscriptions, "execution.messaging_subscriptions.per_plugin");
        int perConnectionSubscriptions = bounded(subscriptions.node("per_connection").getInt(32),
                1, perPluginSubscriptions, "execution.messaging_subscriptions.per_connection");
        return new ExecutionRuntimeConfig(
                configs,
                Duration.ofMillis(scopeGraceMs),
                Duration.ofMillis(runtimeGraceMs),
                globalSubscriptions,
                perPluginSubscriptions,
                perConnectionSubscriptions
        );
    }

    private static LaneConfig lane(
            CommentedConfigurationNode root,
            String name,
            int workers,
            int queue,
            int pluginActive,
            int pluginQueue,
            int connectionActive,
            int connectionQueue
    ) {
        CommentedConfigurationNode node = root.node("execution", "lanes", name);
        return new LaneConfig(
                bounded(node.node("workers").getInt(workers), 1, 256, name + ".workers"),
                bounded(node.node("queue_capacity").getInt(queue), 1, 1_000_000, name + ".queue_capacity"),
                bounded(node.node("per_plugin_active").getInt(pluginActive), 1, 256, name + ".per_plugin_active"),
                bounded(node.node("per_plugin_queue").getInt(pluginQueue), 1, 1_000_000, name + ".per_plugin_queue"),
                bounded(node.node("per_connection_active").getInt(connectionActive), 1, 256,
                        name + ".per_connection_active"),
                bounded(node.node("per_connection_queue").getInt(connectionQueue), 1, 1_000_000,
                        name + ".per_connection_queue")
        );
    }

    private static int bounded(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("Execution config '" + field + "' must be between " + min + " and " + max);
        }
        return value;
    }

    private static long bounded(long value, long min, long max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("Execution config '" + field + "' must be between " + min + " and " + max);
        }
        return value;
    }

    public record LaneConfig(
            int workers,
            int queueCapacity,
            int perPluginActive,
            int perPluginQueue,
            int perConnectionActive,
            int perConnectionQueue
    ) {
        public LaneConfig {
            if (workers < 1 || queueCapacity < 1 || perPluginActive < 1 || perPluginQueue < 1
                    || perConnectionActive < 1 || perConnectionQueue < 1) {
                throw new IllegalArgumentException("Execution limits must be positive.");
            }
            if (perPluginActive > workers) {
                throw new IllegalArgumentException("Per-plugin active limit cannot exceed lane workers.");
            }
            if (perConnectionActive > perPluginActive) {
                throw new IllegalArgumentException("Per-connection active limit cannot exceed per-plugin active limit.");
            }
            if (perPluginQueue > queueCapacity) {
                throw new IllegalArgumentException("Per-plugin queue limit cannot exceed lane queue capacity.");
            }
            if (perConnectionQueue > perPluginQueue) {
                throw new IllegalArgumentException("Per-connection queue limit cannot exceed per-plugin queue limit.");
            }
        }
    }
}
