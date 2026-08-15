package nl.hauntedmc.dataprovider.platform.velocity.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import nl.hauntedmc.dataprovider.core.ProviderLifecycleState;
import nl.hauntedmc.dataprovider.core.resilience.ConnectionHealthSnapshot;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.platform.common.command.DataProviderAdminCommand;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataProviderBrigadierCommandTest {

    @Test
    void statusCommandsRequireStatusPermission() {
        CommandSource source = source(false, false, false);
        CommandDispatcher<CommandSource> dispatcher = dispatcher(handler());

        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("dataprovider status", source));

        verify(source, never()).sendMessage(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void diagnosticConnectionHealthAndConfigCommandsExposeOperationalViews() throws CommandSyntaxException {
        CommandSource source = source(true, true, true);
        CommandDispatcher<CommandSource> dispatcher = dispatcher(handler());

        assertEquals(1, dispatcher.execute("dataprovider diagnostics", source));
        assertEquals(1, dispatcher.execute("dataprovider connections unhealthy", source));
        assertEquals(1, dispatcher.execute("dataprovider connections type mysql", source));
        assertEquals(1, dispatcher.execute("dataprovider health check", source));
        assertEquals(1, dispatcher.execute("dataprovider config", source));
        assertEquals(1, dispatcher.execute("dataprovider reload", source));

        verify(source, atLeast(30)).sendMessage(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void commandTreeOffersAdminSubcommandsAndBackendTypes() throws Exception {
        CommandSource source = source(true, false, false);
        CommandDispatcher<CommandSource> dispatcher = dispatcher(handler());

        List<String> root = dispatcher.getCompletionSuggestions(dispatcher.parse("dataprovider ", source)).get()
                .getList().stream().map(suggestion -> suggestion.getText()).toList();
        List<String> types = dispatcher.getCompletionSuggestions(
                        dispatcher.parse("dataprovider connections type m", source)
                ).get().getList().stream().map(suggestion -> suggestion.getText()).toList();

        assertTrue(root.containsAll(List.of("help", "status", "diagnostics", "connections", "health")));
        assertTrue(types.contains("mysql"));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("dataprovider reload", source));
    }

    private static CommandDispatcher<CommandSource> dispatcher(DataProviderAdminCommand.Handler handler) {
        BrigadierCommand command = DataProviderBrigadierCommand.create(handler);
        CommandDispatcher<CommandSource> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(command.getNode());
        return dispatcher;
    }

    private static CommandSource source(boolean status, boolean config, boolean reload) {
        CommandSource source = mock(CommandSource.class);
        when(source.hasPermission(DataProviderBrigadierCommand.STATUS_PERMISSION)).thenReturn(status);
        when(source.hasPermission(DataProviderBrigadierCommand.CONFIG_PERMISSION)).thenReturn(config);
        when(source.hasPermission(DataProviderBrigadierCommand.RELOAD_PERMISSION)).thenReturn(reload);
        return source;
    }

    private static DataProviderAdminCommand.Handler handler() {
        return new DataProviderAdminCommand.Handler() {
            @Override
            public DataProviderAdminCommand.Snapshot snapshot() {
                return new DataProviderAdminCommand.Snapshot(
                        List.of(
                                new DataProviderAdminCommand.Connection(
                                        "Core", DatabaseType.MYSQL, "main", 2, healthyHealth()
                                ),
                                new DataProviderAdminCommand.Connection(
                                        "Messaging", DatabaseType.REDIS, "pubsub", 1, unhealthyHealth()
                                )
                        ),
                        Map.of(DatabaseType.MYSQL, true, DatabaseType.REDIS, true),
                        "update"
                );
            }

            @Override
            public DataProviderAdminCommand.Config config() {
                return new DataProviderAdminCommand.Config(
                        Map.of(DatabaseType.MYSQL, true, DatabaseType.REDIS, true),
                        "update"
                );
            }

            @Override
            public CompletableFuture<Void> probeHealth() {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void reload() {
                // Successfully reloaded in this command-tree fixture.
            }
        };
    }

    private static ConnectionHealthSnapshot healthyHealth() {
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

    private static ConnectionHealthSnapshot unhealthyHealth() {
        return new ConnectionHealthSnapshot(
                ConnectionHealthSnapshot.LocalConnectionState.DISCONNECTED,
                ConnectionHealthSnapshot.RemoteHealth.UNHEALTHY,
                null,
                ProviderLifecycleState.READY,
                ConnectionHealthSnapshot.RuntimeHealth.UNAVAILABLE,
                ConnectionHealthSnapshot.Circuit.OPEN,
                2, 0, "Connection refused", null, 3, Duration.ofSeconds(5), null
        );
    }
}
