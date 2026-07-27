package nl.hauntedmc.dataprovider.core.concurrent;

import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionRuntimeConfigTest {

    @Test
    void emptyConfigurationUsesDocumentedDefaultsForEveryLane() {
        ExecutionRuntimeConfig config = ExecutionRuntimeConfig.from(CommentedConfigurationNode.root());

        assertEquals(new ExecutionRuntimeConfig.LaneConfig(8, 2_048, 512, 128),
                config.lanes().get(ExecutionLane.RELATIONAL));
        assertEquals(new ExecutionRuntimeConfig.LaneConfig(8, 2_048, 512, 128),
                config.lanes().get(ExecutionLane.DOCUMENT));
        assertEquals(new ExecutionRuntimeConfig.LaneConfig(8, 2_048, 512, 128),
                config.lanes().get(ExecutionLane.REDIS));
        assertEquals(new ExecutionRuntimeConfig.LaneConfig(8, 4_096, 1_024, 256),
                config.lanes().get(ExecutionLane.MESSAGING));
        assertEquals(Duration.ofSeconds(2), config.scopeShutdownGrace());
        assertEquals(Duration.ofSeconds(5), config.runtimeShutdownGrace());
        assertEquals(256, config.messagingGlobalSubscriptions());
        assertEquals(64, config.messagingPerPluginSubscriptions());
        assertEquals(32, config.messagingPerConnectionSubscriptions());
    }

    @Test
    void readsCustomSettingsForEveryExecutionDimension() {
        CommentedConfigurationNode root = CommentedConfigurationNode.root();
        int index = 1;
        for (ExecutionLane lane : ExecutionLane.values()) {
            String name = lane.name().toLowerCase(java.util.Locale.ROOT);
            root.node("execution", "lanes", name, "workers").raw(index + 1);
            root.node("execution", "lanes", name, "queue_capacity").raw(100 + index);
            root.node("execution", "lanes", name, "per_plugin_queue").raw(50 + index);
            root.node("execution", "lanes", name, "per_resource_queue").raw(20 + index);
            index++;
        }
        root.node("execution", "scope_shutdown_grace_ms").raw(1_234L);
        root.node("execution", "runtime_shutdown_grace_ms").raw(5_678L);
        root.node("execution", "messaging_subscriptions", "global").raw(90);
        root.node("execution", "messaging_subscriptions", "per_plugin").raw(45);
        root.node("execution", "messaging_subscriptions", "per_connection").raw(15);

        ExecutionRuntimeConfig config = ExecutionRuntimeConfig.from(root);

        assertEquals(new ExecutionRuntimeConfig.LaneConfig(2, 101, 51, 21),
                config.lanes().get(ExecutionLane.RELATIONAL));
        assertEquals(new ExecutionRuntimeConfig.LaneConfig(5, 104, 54, 24),
                config.lanes().get(ExecutionLane.MESSAGING));
        assertEquals(Duration.ofMillis(1_234), config.scopeShutdownGrace());
        assertEquals(Duration.ofMillis(5_678), config.runtimeShutdownGrace());
        assertEquals(90, config.messagingGlobalSubscriptions());
        assertEquals(45, config.messagingPerPluginSubscriptions());
        assertEquals(15, config.messagingPerConnectionSubscriptions());
    }

    @Test
    void constructorDefensivelyCopiesAndExposesAnImmutableLaneMap() {
        EnumMap<ExecutionLane, ExecutionRuntimeConfig.LaneConfig> lanes = validLanes();
        ExecutionRuntimeConfig.LaneConfig original = lanes.get(ExecutionLane.REDIS);
        ExecutionRuntimeConfig config = config(lanes, Duration.ZERO, Duration.ZERO, 8, 4, 2);

        lanes.put(ExecutionLane.REDIS, new ExecutionRuntimeConfig.LaneConfig(9, 9, 9, 9));

        assertEquals(original, config.lanes().get(ExecutionLane.REDIS));
        assertThrows(UnsupportedOperationException.class,
                () -> config.lanes().put(ExecutionLane.REDIS, original));
    }

    @Test
    void constructorRejectsMissingLanesNullValuesAndNegativeGrace() {
        EnumMap<ExecutionLane, ExecutionRuntimeConfig.LaneConfig> missing = validLanes();
        missing.remove(ExecutionLane.MESSAGING);

        assertThrows(NullPointerException.class,
                () -> config(null, Duration.ZERO, Duration.ZERO, 8, 4, 2));
        assertThrows(IllegalArgumentException.class,
                () -> config(missing, Duration.ZERO, Duration.ZERO, 8, 4, 2));
        assertThrows(NullPointerException.class,
                () -> config(validLanes(), null, Duration.ZERO, 8, 4, 2));
        assertThrows(NullPointerException.class,
                () -> config(validLanes(), Duration.ZERO, null, 8, 4, 2));
        assertThrows(IllegalArgumentException.class,
                () -> config(validLanes(), Duration.ofMillis(-1), Duration.ZERO, 8, 4, 2));
        assertThrows(IllegalArgumentException.class,
                () -> config(validLanes(), Duration.ZERO, Duration.ofMillis(-1), 8, 4, 2));
    }

    @Test
    void constructorRejectsInvalidSubscriptionLimitHierarchies() {
        assertThrows(IllegalArgumentException.class,
                () -> config(validLanes(), Duration.ZERO, Duration.ZERO, 0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> config(validLanes(), Duration.ZERO, Duration.ZERO, 8, 9, 1));
        assertThrows(IllegalArgumentException.class,
                () -> config(validLanes(), Duration.ZERO, Duration.ZERO, 8, 4, 5));
    }

    @Test
    void laneConfigRejectsNonPositiveAndOverCapacityLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionRuntimeConfig.LaneConfig(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionRuntimeConfig.LaneConfig(1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionRuntimeConfig.LaneConfig(1, 4, 5, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ExecutionRuntimeConfig.LaneConfig(1, 4, 1, 5));
    }

    @Test
    void parserRejectsOutOfRangeLaneAndShutdownSettings() {
        CommentedConfigurationNode invalidWorkers = CommentedConfigurationNode.root();
        invalidWorkers.node("execution", "lanes", "relational", "workers").raw(0);
        assertThrows(IllegalArgumentException.class, () -> ExecutionRuntimeConfig.from(invalidWorkers));

        CommentedConfigurationNode invalidQueue = CommentedConfigurationNode.root();
        invalidQueue.node("execution", "lanes", "document", "queue_capacity").raw(1_000_001);
        assertThrows(IllegalArgumentException.class, () -> ExecutionRuntimeConfig.from(invalidQueue));

        CommentedConfigurationNode invalidScopeGrace = CommentedConfigurationNode.root();
        invalidScopeGrace.node("execution", "scope_shutdown_grace_ms").raw(60_001L);
        assertThrows(IllegalArgumentException.class, () -> ExecutionRuntimeConfig.from(invalidScopeGrace));

        CommentedConfigurationNode invalidRuntimeGrace = CommentedConfigurationNode.root();
        invalidRuntimeGrace.node("execution", "runtime_shutdown_grace_ms").raw(120_001L);
        assertThrows(IllegalArgumentException.class, () -> ExecutionRuntimeConfig.from(invalidRuntimeGrace));
    }

    @Test
    void parserAppliesDynamicSubscriptionBounds() {
        CommentedConfigurationNode excessivePerPlugin = CommentedConfigurationNode.root();
        excessivePerPlugin.node("execution", "messaging_subscriptions", "global").raw(10);
        excessivePerPlugin.node("execution", "messaging_subscriptions", "per_plugin").raw(11);
        assertThrows(IllegalArgumentException.class, () -> ExecutionRuntimeConfig.from(excessivePerPlugin));

        CommentedConfigurationNode excessivePerConnection = CommentedConfigurationNode.root();
        excessivePerConnection.node("execution", "messaging_subscriptions", "global").raw(10);
        excessivePerConnection.node("execution", "messaging_subscriptions", "per_plugin").raw(5);
        excessivePerConnection.node("execution", "messaging_subscriptions", "per_connection").raw(6);
        assertThrows(IllegalArgumentException.class, () -> ExecutionRuntimeConfig.from(excessivePerConnection));
    }

    private static EnumMap<ExecutionLane, ExecutionRuntimeConfig.LaneConfig> validLanes() {
        EnumMap<ExecutionLane, ExecutionRuntimeConfig.LaneConfig> lanes = new EnumMap<>(ExecutionLane.class);
        for (ExecutionLane lane : ExecutionLane.values()) {
            lanes.put(lane, new ExecutionRuntimeConfig.LaneConfig(1, 8, 4, 2));
        }
        return lanes;
    }

    private static ExecutionRuntimeConfig config(
            Map<ExecutionLane, ExecutionRuntimeConfig.LaneConfig> lanes,
            Duration scopeGrace,
            Duration runtimeGrace,
            int globalSubscriptions,
            int pluginSubscriptions,
            int connectionSubscriptions
    ) {
        return new ExecutionRuntimeConfig(
                lanes,
                scopeGrace,
                runtimeGrace,
                globalSubscriptions,
                pluginSubscriptions,
                connectionSubscriptions
        );
    }
}
