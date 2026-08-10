package nl.hauntedmc.dataprovider.platform.bukkit.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
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
    void showsHelpWhenNoArgumentsAreProvided() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommand command = new DataProviderCommand(handler);
        RecordingBukkitSender sender = new RecordingBukkitSender();

        command.execute(sender.sender(), new String[0]);

        assertTrue(sender.hasMessageContaining("DataProvider command help:"));
        verify(handler, never()).getActiveDatabases();
    }

    @Test
    void statusRequiresPermission() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommand command = new DataProviderCommand(handler);
        RecordingBukkitSender sender = new RecordingBukkitSender();

        command.execute(sender.sender(), new String[]{"status"});

        assertTrue(sender.hasMessageContaining("Missing permission: dataprovider.command.status"));
        verify(handler, never()).getActiveDatabases();
    }

    @Test
    void statusDisplaysEmptyAndPopulatedConnectionStates() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommand command = new DataProviderCommand(handler);
        RecordingBukkitSender sender = new RecordingBukkitSender();
        sender.grantPermission("dataprovider.command.status");

        when(handler.getActiveDatabases()).thenReturn(new ConcurrentHashMap<>());
        when(handler.getActiveDatabaseReferenceCounts()).thenReturn(Map.of());

        command.execute(sender.sender(), new String[]{"status"});
        assertTrue(sender.hasMessageContaining("No active database connections found."));

        ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> active = new ConcurrentHashMap<>();
        DatabaseConnectionKey key = new DatabaseConnectionKey("FeatureA", DatabaseType.MYSQL, "default");
        active.put(key, mock(DatabaseProvider.class));
        when(handler.getActiveDatabases()).thenReturn(active);
        when(handler.getActiveDatabaseReferenceCounts()).thenReturn(Map.of(key, 2));

        command.execute(sender.sender(), new String[]{"status"});

        assertTrue(sender.hasMessageContaining("DataProvider Status"));
        assertTrue(sender.hasMessageContaining("plugin=FeatureA"));
    }

    @Test
    void unknownSubcommandReturnsErrorMessage() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommand command = new DataProviderCommand(handler);
        RecordingBukkitSender sender = new RecordingBukkitSender();

        command.execute(sender.sender(), new String[]{"unknown"});

        assertTrue(sender.hasMessageContaining("Unknown subcommand"));
    }

    @Test
    void tabCompletionSuggestsStatusAndHelp() {
        DataProviderCommand command = new DataProviderCommand(mock(DataProviderHandler.class));
        RecordingBukkitSender sender = new RecordingBukkitSender();
        sender.grantPermission("dataprovider.command.status");
        List<String> completions = command.suggest(sender.sender(), new String[]{"s"});
        assertEquals(List.of("status"), completions);

        List<String> helpCompletions = command.suggest(sender.sender(), new String[]{"h"});
        assertEquals(List.of("help"), helpCompletions);

        List<String> none = command.suggest(sender.sender(), new String[]{"x"});
        assertTrue(none.isEmpty());
    }

    @Test
    void rootCommandIsHiddenFromPlayersWithoutAnOperationalPermission() {
        DataProviderCommand command = new DataProviderCommand(mock(DataProviderHandler.class));
        RecordingBukkitSender sender = new RecordingBukkitSender();

        assertFalse(command.canUse(sender.player()));
        sender.grantPermission("dataprovider.command.reload");
        assertTrue(command.canUse(sender.player()));
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
            String methodName = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                if ("toString".equals(methodName)) {
                    return "RecordingBukkitSender";
                }
                if ("hashCode".equals(methodName)) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(methodName)) {
                    return proxy == args[0];
                }
            }
            if ("hasPermission".equals(methodName) && args != null && args.length == 1 && args[0] instanceof String permission) {
                return permissions.contains(permission);
            }
            if ("sendMessage".equals(methodName) && args != null && args.length == 1 && args[0] instanceof Component component) {
                messages.add(component);
                return null;
            }
            return defaultValue(method.getReturnType());
        }

        private static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive()) {
                return null;
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == byte.class) {
                return (byte) 0;
            }
            if (returnType == short.class) {
                return (short) 0;
            }
            if (returnType == int.class) {
                return 0;
            }
            if (returnType == long.class) {
                return 0L;
            }
            if (returnType == float.class) {
                return 0f;
            }
            if (returnType == double.class) {
                return 0d;
            }
            if (returnType == char.class) {
                return '\0';
            }
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
            return messages.stream()
                    .map(component -> PlainTextComponentSerializer.plainText().serialize(component))
                    .anyMatch(text -> text.contains(fragment));
        }
    }
}
