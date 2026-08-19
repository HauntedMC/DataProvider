package nl.hauntedmc.dataprovider.platform.bukkit.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.core.resilience.ConnectionHealthSnapshot;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataProviderCommandTest {

    @Test
    void helpUsesTheSharedAdministrativePresentation() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommand command = new DataProviderCommand(handler);
        RecordingBukkitSender sender = new RecordingBukkitSender();
        sender.grantPermission(DataProviderCommand.STATUS_PERMISSION);

        command.execute(sender.sender(), new String[0]);

        assertTrue(sender.hasMessageContaining("DataProvider administration"));
        assertTrue(sender.hasMessageContaining("/dataprovider health [check]"));
        verify(handler, never()).getActiveDatabases();
    }

    @Test
    void diagnosticsArePermissionGatedAndUseCachedHealth() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommand command = new DataProviderCommand(handler);
        RecordingBukkitSender sender = new RecordingBukkitSender();

        command.execute(sender.sender(), new String[]{"diagnostics"});

        assertTrue(sender.hasMessageContaining("Missing permission: dataprovider.command.status"));
        verify(handler, never()).getActiveDatabases();

        sender.grantPermission(DataProviderCommand.STATUS_PERMISSION);
        configureSingleConnection(handler, 2);
        command.execute(sender.sender(), new String[]{"diagnostics"});

        assertTrue(sender.hasMessageContaining("DataProvider diagnostics"));
        assertTrue(sender.hasMessageContaining("FeatureA / default"));
        assertTrue(sender.hasMessageContaining("resilience"));
    }

    @Test
    void connectionsFilteringAndCompletionUseTheSameCommandSurface() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommand command = new DataProviderCommand(handler);
        RecordingBukkitSender sender = new RecordingBukkitSender();
        sender.grantPermission(DataProviderCommand.STATUS_PERMISSION);
        configureSingleConnection(handler, 1);

        command.execute(sender.sender(), new String[]{"connections", "plugin", "FeatureA"});

        assertTrue(sender.hasMessageContaining("Filtered connections"));
        assertTrue(sender.hasMessageContaining("Matches  »  1"));
        assertEquals(List.of("connections"), command.suggest(sender.sender(), new String[]{"c"}));
        assertEquals(List.of("FeatureA"), command.suggest(sender.sender(),
                new String[]{"connections", "plugin", "fea"}));
        assertEquals(List.of("mysql"), command.suggest(sender.sender(),
                new String[]{"connections", "type", "mys"}));
    }

    @Test
    void healthCheckReturnsItsResultThroughTheProvidedMainThreadExecutor() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        CompletableFuture<Void> probe = new CompletableFuture<>();
        when(handler.probeDatabaseHealthAsync()).thenReturn(probe);
        when(handler.getActiveDatabases()).thenReturn(new ConcurrentHashMap<>());
        when(handler.getActiveDatabaseReferenceCounts()).thenReturn(Map.of());
        when(handler.getCachedDatabaseHealth()).thenReturn(Map.of());
        when(handler.getConfiguredDatabaseTypeStates()).thenReturn(Map.of());
        when(handler.getConfiguredOrmSchemaMode()).thenReturn("validate");
        List<Runnable> scheduled = new ArrayList<>();
        DataProviderCommand command = new DataProviderCommand(handler, scheduled::add);
        RecordingBukkitSender sender = new RecordingBukkitSender();
        sender.grantPermission(DataProviderCommand.STATUS_PERMISSION);

        command.execute(sender.sender(), new String[]{"health", "check"});
        probe.complete(null);

        assertEquals(1, scheduled.size());
        scheduled.forEach(Runnable::run);
        assertTrue(sender.hasMessageContaining("Running remote database health checks"));
        assertTrue(sender.hasMessageContaining("Connection health"));
    }

    @Test
    void configAndReloadExposeUsefulStateAndPreserveTheirPermissions() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        when(handler.getConfiguredDatabaseTypeStates()).thenReturn(Map.of(DatabaseType.MYSQL, true, DatabaseType.REDIS, false));
        when(handler.getConfiguredOrmSchemaMode()).thenReturn("validate");
        DataProviderCommand command = new DataProviderCommand(handler);
        RecordingBukkitSender sender = new RecordingBukkitSender();

        command.execute(sender.sender(), new String[]{"config"});
        assertTrue(sender.hasMessageContaining("Missing permission: dataprovider.command.config"));

        sender.grantPermission(DataProviderCommand.CONFIG_PERMISSION);
        command.execute(sender.sender(), new String[]{"config"});
        assertTrue(sender.hasMessageContaining("DataProvider configuration"));
        assertTrue(sender.hasMessageContaining("MYSQL  »  ENABLED"));
        assertTrue(sender.hasMessageContaining("REDIS  »  DISABLED"));

        command.execute(sender.sender(), new String[]{"reload"});
        assertTrue(sender.hasMessageContaining("Missing permission: dataprovider.command.reload"));
        sender.grantPermission(DataProviderCommand.RELOAD_PERMISSION);
        command.execute(sender.sender(), new String[]{"reload"});
        verify(handler).reloadConfiguration();
        assertTrue(sender.hasMessageContaining("Reloaded the validated DataProvider configuration."));
    }

    @Test
    void rootCommandIsHiddenFromPlayersWithoutAnOperationalPermission() {
        DataProviderCommand command = new DataProviderCommand(mock(DataProviderHandler.class));
        RecordingBukkitSender sender = new RecordingBukkitSender();

        assertFalse(command.canUse(sender.player()));
        assertTrue(command.canUse(sender.sender()));
        sender.grantPermission(DataProviderCommand.RELOAD_PERMISSION);
        assertTrue(command.canUse(sender.player()));
    }

    private static void configureSingleConnection(DataProviderHandler handler, int references) {
        DatabaseConnectionKey key = new DatabaseConnectionKey("FeatureA", DatabaseType.MYSQL, "default");
        ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> active = new ConcurrentHashMap<>();
        active.put(key, mock(DatabaseProvider.class));
        when(handler.getActiveDatabases()).thenReturn(active);
        when(handler.getActiveDatabaseReferenceCounts()).thenReturn(Map.of(key, references));
        when(handler.getCachedDatabaseHealth()).thenReturn(Map.of(key, ConnectionHealthSnapshot.unprobed(false)));
        when(handler.getConfiguredDatabaseTypeStates()).thenReturn(Map.of(DatabaseType.MYSQL, true));
        when(handler.getConfiguredOrmSchemaMode()).thenReturn("validate");
    }

    private static final class RecordingBukkitSender {
        private final Set<String> permissions = new HashSet<>();
        private final List<Component> messages = new ArrayList<>();
        private final CommandSender sender = (CommandSender) Proxy.newProxyInstance(
                DataProviderCommandTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                this::invoke
        );

        private Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "RecordingBukkitSender";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            }
            if ("hasPermission".equals(method.getName()) && args != null && args.length == 1 && args[0] instanceof String permission) {
                return permissions.contains(permission);
            }
            if ("sendMessage".equals(method.getName()) && args != null && args.length == 1 && args[0] instanceof Component component) {
                messages.add(component);
                return null;
            }
            return defaultValue(method.getReturnType());
        }

        private static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive()) return null;
            if (returnType == boolean.class) return false;
            if (returnType == byte.class) return (byte) 0;
            if (returnType == short.class) return (short) 0;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            if (returnType == float.class) return 0f;
            if (returnType == double.class) return 0d;
            if (returnType == char.class) return '\0';
            return null;
        }

        private CommandSender sender() {
            return sender;
        }

        private CommandSender player() {
            return (CommandSender) Proxy.newProxyInstance(
                    DataProviderCommandTest.class.getClassLoader(),
                    new Class<?>[]{Player.class},
                    this::invoke
            );
        }

        private void grantPermission(String permission) {
            permissions.add(permission);
        }

        private boolean hasMessageContaining(String fragment) {
            return messages.stream().map(component -> PlainTextComponentSerializer.plainText().serialize(component))
                    .anyMatch(text -> text.contains(fragment));
        }
    }
}
