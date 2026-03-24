package nl.hauntedmc.dataprovider.platform.velocity.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataProviderCommandTest {

    @Test
    void executeHandlesHelpPermissionAndUnknownSubcommands() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommand command = new DataProviderCommand(handler);
        RecordingVelocitySource source = new RecordingVelocitySource();

        command.execute(new TestInvocation(source.source(), new String[0]));
        assertTrue(source.hasMessageContaining("Usage: /dataprovider status"));

        command.execute(new TestInvocation(source.source(), new String[]{"status"}));
        assertTrue(source.hasMessageContaining("do not have permission"));

        command.execute(new TestInvocation(source.source(), new String[]{"unknown"}));
        assertTrue(source.hasMessageContaining("Unknown subcommand"));
    }

    @Test
    void executeStatusShowsEmptyAndPopulatedConnections() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommand command = new DataProviderCommand(handler);
        RecordingVelocitySource source = new RecordingVelocitySource();
        source.grantPermission("dataprovider.command.status");

        when(handler.getActiveDatabases()).thenReturn(new ConcurrentHashMap<>());
        when(handler.getActiveDatabaseReferenceCounts()).thenReturn(Map.of());
        command.execute(new TestInvocation(source.source(), new String[]{"status"}));
        assertTrue(source.hasMessageContaining("No active database connections found."));

        ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> active = new ConcurrentHashMap<>();
        DatabaseConnectionKey key = new DatabaseConnectionKey("VelocityFeature", DatabaseType.REDIS, "pubsub");
        active.put(key, mock(DatabaseProvider.class));
        when(handler.getActiveDatabases()).thenReturn(active);
        when(handler.getActiveDatabaseReferenceCounts()).thenReturn(Map.of(key, 3));

        command.execute(new TestInvocation(source.source(), new String[]{"status"}));
        assertTrue(source.hasMessageContaining("Active Database Connections:"));
        assertTrue(source.hasMessageContaining("VelocityFeature"));
    }

    @Test
    void suggestAsyncReturnsExpectedCompletions() {
        DataProviderCommand command = new DataProviderCommand(mock(DataProviderHandler.class));
        RecordingVelocitySource source = new RecordingVelocitySource();
        assertEquals(List.of("status"), command.suggestAsync(new TestInvocation(source.source(), new String[]{"s"})).join());
        assertEquals(List.of("help"), command.suggestAsync(new TestInvocation(source.source(), new String[]{"h"})).join());
        assertTrue(command.suggestAsync(new TestInvocation(source.source(), new String[]{"x"})).join().isEmpty());
    }

    private record TestInvocation(CommandSource source, String[] arguments) implements SimpleCommand.Invocation {
        @Override
        public String alias() {
            return "dataprovider";
        }
    }

    private static final class RecordingVelocitySource {
        private final Set<String> permissions = new HashSet<>();
        private final List<Component> messages = new ArrayList<>();
        private final CommandSource source = (CommandSource) Proxy.newProxyInstance(
                DataProviderCommandTest.class.getClassLoader(),
                new Class<?>[]{CommandSource.class},
                this::invoke
        );

        private Object invoke(Object proxy, Method method, Object[] args) {
            String methodName = method.getName();
            if (method.getDeclaringClass() == Object.class) {
                if ("toString".equals(methodName)) {
                    return "RecordingVelocitySource";
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

        private CommandSource source() {
            return source;
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
