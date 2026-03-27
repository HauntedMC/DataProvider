package nl.hauntedmc.dataprovider.platform.internal.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataProviderCommandServiceTest {

    @Test
    void executeShowsUsageForEmptyArgsAndHelpSubcommand() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommandService service = new DataProviderCommandService(handler);
        RecordingOutput output = new RecordingOutput();

        service.execute(new String[0], alwaysDenied(), output::record);
        service.execute(new String[]{"help"}, alwaysDenied(), output::record);

        assertTrue(output.hasMessageContaining("Usage: /dataprovider status"));
        verify(handler, never()).getActiveDatabases();
    }

    @Test
    void executeStatusRequiresPermission() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommandService service = new DataProviderCommandService(handler);
        RecordingOutput output = new RecordingOutput();

        service.execute(new String[]{"status"}, alwaysDenied(), output::record);

        assertTrue(output.hasMessageContaining("do not have permission"));
        verify(handler, never()).getActiveDatabases();
    }

    @Test
    void executeStatusShowsEmptyAndPopulatedStates() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommandService service = new DataProviderCommandService(handler);
        RecordingOutput output = new RecordingOutput();

        when(handler.getActiveDatabases()).thenReturn(new ConcurrentHashMap<>());
        when(handler.getActiveDatabaseReferenceCounts()).thenReturn(Map.of());
        service.execute(new String[]{"status"}, alwaysGranted(), output::record);
        assertTrue(output.hasMessageContaining("No active database connections found."));

        ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> activeDatabases = new ConcurrentHashMap<>();
        DatabaseConnectionKey keyA = new DatabaseConnectionKey("APlugin", DatabaseType.REDIS, "cache");
        DatabaseConnectionKey keyB = new DatabaseConnectionKey("BPlugin", DatabaseType.MYSQL, "default");
        activeDatabases.put(keyB, mock(DatabaseProvider.class));
        activeDatabases.put(keyA, mock(DatabaseProvider.class));
        when(handler.getActiveDatabases()).thenReturn(activeDatabases);
        when(handler.getActiveDatabaseReferenceCounts()).thenReturn(Map.of(keyA, 1, keyB, 3));

        output.clear();
        service.execute(new String[]{"status"}, alwaysGranted(), output::record);

        assertEquals("Active Database Connections:", output.messageAt(0));
        assertTrue(output.messageAt(1).contains("Plugin: APlugin"));
        assertTrue(output.messageAt(2).contains("Plugin: BPlugin"));
    }

    @Test
    void executeUnknownSubcommandShowsError() {
        DataProviderCommandService service = new DataProviderCommandService(mock(DataProviderHandler.class));
        RecordingOutput output = new RecordingOutput();

        service.execute(new String[]{"unknown"}, alwaysGranted(), output::record);

        assertTrue(output.hasMessageContaining("Unknown subcommand"));
    }

    @Test
    void suggestReturnsExpectedRootCompletions() {
        DataProviderCommandService service = new DataProviderCommandService(mock(DataProviderHandler.class));

        assertEquals(List.of("status"), service.suggest(new String[]{"s"}));
        assertEquals(List.of("help"), service.suggest(new String[]{"h"}));
        assertTrue(service.suggest(new String[]{"x"}).isEmpty());
        assertTrue(service.suggest(new String[0]).isEmpty());
        assertTrue(service.suggest(new String[]{"status", "extra"}).isEmpty());
    }

    private static Predicate<String> alwaysGranted() {
        return permission -> true;
    }

    private static Predicate<String> alwaysDenied() {
        return permission -> false;
    }

    private static final class RecordingOutput {
        private final List<String> messages = new ArrayList<>();

        private void record(Component message) {
            messages.add(PlainTextComponentSerializer.plainText().serialize(message));
        }

        private boolean hasMessageContaining(String fragment) {
            return messages.stream().anyMatch(text -> text.contains(fragment));
        }

        private String messageAt(int index) {
            return messages.get(index);
        }

        private void clear() {
            messages.clear();
        }
    }
}
