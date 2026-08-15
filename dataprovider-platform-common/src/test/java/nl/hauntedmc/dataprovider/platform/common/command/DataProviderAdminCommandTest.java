package nl.hauntedmc.dataprovider.platform.common.command;

import net.kyori.adventure.text.Component;
import nl.hauntedmc.dataprovider.core.ProviderLifecycleState;
import nl.hauntedmc.dataprovider.core.resilience.ConnectionHealthSnapshot;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataProviderAdminCommandTest {

    @Test
    void administratorViewsShareTheSameDiagnosticsAndCompletionBehavior() {
        RecordingHandler handler = new RecordingHandler();
        DataProviderAdminCommand command = new DataProviderAdminCommand(handler);
        RecordingSource source = RecordingSource.administrator();

        command.execute(new String[0], source);
        command.execute(new String[]{"status"}, source);
        command.execute(new String[]{"status", "summary"}, source);
        command.execute(new String[]{"diagnostics"}, source);
        command.execute(new String[]{"connections"}, source);
        command.execute(new String[]{"connections", "unhealthy"}, source);
        command.execute(new String[]{"connections", "plugin", "Core"}, source);
        command.execute(new String[]{"connections", "type", "mysql"}, source);
        command.execute(new String[]{"health"}, source);
        command.execute(new String[]{"health", "check"}, source);
        command.execute(new String[]{"config"}, source);
        command.execute(new String[]{"reload"}, source);

        assertEquals(1, handler.reloads);
        assertEquals(1, source.dispatchedCompletions);
        assertTrue(source.messages.size() >= 40);
        assertEquals(List.of("Core"), command.suggest(new String[]{"connections", "plugin", "co"}, source));
        assertEquals(List.of("mysql"), command.suggest(new String[]{"connections", "type", "my"}, source));
    }

    @Test
    void protectedCommandsDoNotLeakThroughSuggestionsOrExecuteWithoutPermission() {
        DataProviderAdminCommand command = new DataProviderAdminCommand(new RecordingHandler());
        RecordingSource source = new RecordingSource(Set.of());

        command.execute(new String[]{"status"}, source);

        assertEquals(List.of("help"), command.suggest(new String[]{""}, source));
        assertTrue(source.messages.stream().map(Component::toString)
                .anyMatch(message -> message.contains(DataProviderAdminCommand.STATUS_PERMISSION)));
    }

    private static final class RecordingHandler implements DataProviderAdminCommand.Handler {
        private int reloads;

        @Override
        public DataProviderAdminCommand.Snapshot snapshot() {
            return new DataProviderAdminCommand.Snapshot(
                    List.of(
                            new DataProviderAdminCommand.Connection("Core", DatabaseType.MYSQL, "main", 2, healthy()),
                            new DataProviderAdminCommand.Connection("Messaging", DatabaseType.REDIS, "events", 1, unhealthy())
                    ),
                    Map.of(DatabaseType.MYSQL, true, DatabaseType.REDIS, true),
                    "validate"
            );
        }

        @Override
        public DataProviderAdminCommand.Config config() {
            return new DataProviderAdminCommand.Config(
                    Map.of(DatabaseType.MYSQL, true, DatabaseType.REDIS, true),
                    "validate"
            );
        }

        @Override
        public CompletableFuture<Void> probeHealth() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void reload() {
            reloads++;
        }

        private static ConnectionHealthSnapshot healthy() {
            return new ConnectionHealthSnapshot(
                    ConnectionHealthSnapshot.LocalConnectionState.CONNECTED,
                    ConnectionHealthSnapshot.RemoteHealth.HEALTHY,
                    null,
                    ProviderLifecycleState.READY,
                    ConnectionHealthSnapshot.RuntimeHealth.HEALTHY,
                    ConnectionHealthSnapshot.Circuit.CLOSED,
                    0, 0, null, null, 0, Duration.ZERO, null
            );
        }

        private static ConnectionHealthSnapshot unhealthy() {
            return new ConnectionHealthSnapshot(
                    ConnectionHealthSnapshot.LocalConnectionState.DISCONNECTED,
                    ConnectionHealthSnapshot.RemoteHealth.UNHEALTHY,
                    null,
                    ProviderLifecycleState.READY,
                    ConnectionHealthSnapshot.RuntimeHealth.UNAVAILABLE,
                    ConnectionHealthSnapshot.Circuit.OPEN,
                    2, 0, "Connection refused", null, 1, Duration.ZERO, null
            );
        }
    }

    private static final class RecordingSource implements DataProviderAdminCommand.Source {
        private final Set<String> permissions;
        private final List<Component> messages = new ArrayList<>();
        private int dispatchedCompletions;

        private RecordingSource(Set<String> permissions) {
            this.permissions = new HashSet<>(permissions);
        }

        private static RecordingSource administrator() {
            return new RecordingSource(Set.of(
                    DataProviderAdminCommand.STATUS_PERMISSION,
                    DataProviderAdminCommand.CONFIG_PERMISSION,
                    DataProviderAdminCommand.RELOAD_PERMISSION
            ));
        }

        @Override
        public boolean hasPermission(String permission) {
            return permissions.contains(permission);
        }

        @Override
        public void sendMessage(Component message) {
            messages.add(message);
        }

        @Override
        public void dispatchCompletion(Runnable task) {
            dispatchedCompletions++;
            task.run();
        }
    }
}
