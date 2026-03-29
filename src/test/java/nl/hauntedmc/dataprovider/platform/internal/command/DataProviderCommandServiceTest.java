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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataProviderCommandServiceTest {

    @Test
    void executeShowsHelpForEmptyArgsAndHelpSubcommand() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommandService service = new DataProviderCommandService(handler);
        RecordingOutput output = new RecordingOutput();

        service.execute(new String[0], deniedPermissions(), output::record);
        service.execute(new String[]{"help"}, deniedPermissions(), output::record);

        assertTrue(output.hasMessageContaining("DataProvider command help:"));
        assertTrue(output.hasMessageContaining("/dataprovider status"));
        verify(handler, never()).getActiveDatabases();
    }

    @Test
    void executeStatusRequiresPermission() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommandService service = new DataProviderCommandService(handler);
        RecordingOutput output = new RecordingOutput();

        service.execute(new String[]{"status"}, deniedPermissions(), output::record);

        assertTrue(output.hasMessageContaining("Missing permission: dataprovider.command.status"));
        verify(handler, never()).getActiveDatabases();
    }

    @Test
    void executeStatusShowsOverviewAggregatesAndConnectionRows() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommandService service = new DataProviderCommandService(handler);
        RecordingOutput output = new RecordingOutput();

        DatabaseConnectionKey keyA = new DatabaseConnectionKey("APlugin", DatabaseType.REDIS, "cache");
        DatabaseConnectionKey keyB = new DatabaseConnectionKey("BPlugin", DatabaseType.MYSQL, "default");
        ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> activeDatabases = new ConcurrentHashMap<>();
        activeDatabases.put(keyA, connectedProvider());
        activeDatabases.put(keyB, disconnectedProvider());
        when(handler.getActiveDatabases()).thenReturn(activeDatabases);
        when(handler.getActiveDatabaseReferenceCounts()).thenReturn(Map.of(keyA, 2, keyB, 3));

        service.execute(new String[]{"status"}, permissions("dataprovider.command.status"), output::record);

        assertTrue(output.hasMessageContaining("DataProvider Status"));
        assertTrue(output.hasMessageContaining("connections=2"));
        assertTrue(output.hasMessageContaining("By plugin:"));
        assertTrue(output.hasMessageContaining("By backend:"));
        assertTrue(output.hasMessageContaining("Connections:"));
        assertTrue(output.hasMessageContaining("plugin=APlugin"));
        assertTrue(output.hasMessageContaining("state=CONNECTED"));
        assertTrue(output.hasMessageContaining("state=DISCONNECTED"));
    }

    @Test
    void executeStatusSupportsSummaryAndFilters() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommandService service = new DataProviderCommandService(handler);
        RecordingOutput output = new RecordingOutput();

        DatabaseConnectionKey keyA = new DatabaseConnectionKey("FeatureA", DatabaseType.REDIS, "cache");
        DatabaseConnectionKey keyB = new DatabaseConnectionKey("FeatureA", DatabaseType.MYSQL, "main");
        ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> activeDatabases = new ConcurrentHashMap<>();
        activeDatabases.put(keyA, connectedProvider());
        activeDatabases.put(keyB, connectedProvider());
        when(handler.getActiveDatabases()).thenReturn(activeDatabases);
        when(handler.getActiveDatabaseReferenceCounts()).thenReturn(Map.of(keyA, 1, keyB, 1));

        service.execute(
                new String[]{"status", "summary", "plugin", "FeatureA", "type", "redis"},
                permissions("dataprovider.command.status"),
                output::record
        );

        assertTrue(output.hasMessageContaining("connections=1"));
        assertTrue(output.hasMessageContaining("filters: plugin=FeatureA"));
        assertTrue(output.hasMessageContaining("type=REDIS"));
        assertTrue(output.hasMessageContaining("view=summary"));
        assertFalse(output.hasMessageContaining("Connections:"));
    }

    @Test
    void executeStatusSupportsUnhealthyFiltering() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommandService service = new DataProviderCommandService(handler);
        RecordingOutput output = new RecordingOutput();

        DatabaseConnectionKey healthyKey = new DatabaseConnectionKey("HealthyPlugin", DatabaseType.REDIS, "cache");
        DatabaseConnectionKey unhealthyKey = new DatabaseConnectionKey("UnhealthyPlugin", DatabaseType.REDIS, "cache");
        ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> activeDatabases = new ConcurrentHashMap<>();
        activeDatabases.put(healthyKey, connectedProvider());
        activeDatabases.put(unhealthyKey, disconnectedProvider());
        when(handler.getActiveDatabases()).thenReturn(activeDatabases);
        when(handler.getActiveDatabaseReferenceCounts()).thenReturn(Map.of(healthyKey, 1, unhealthyKey, 1));

        service.execute(
                new String[]{"status", "unhealthy"},
                permissions("dataprovider.command.status"),
                output::record
        );

        assertTrue(output.hasMessageContaining("connections=1"));
        assertTrue(output.hasMessageContaining("plugin=UnhealthyPlugin"));
        assertFalse(output.hasMessageContaining("plugin=HealthyPlugin"));
        assertTrue(output.hasMessageContaining("health=unhealthy"));
    }

    @Test
    void executeStatusRejectsInvalidOptions() {
        DataProviderCommandService service = new DataProviderCommandService(mock(DataProviderHandler.class));
        RecordingOutput output = new RecordingOutput();

        service.execute(
                new String[]{"status", "unknown-option"},
                permissions("dataprovider.command.status"),
                output::record
        );

        assertTrue(output.hasMessageContaining("Unknown status option"));
        assertTrue(output.hasMessageContaining("Usage: /dataprovider status"));
    }

    @Test
    void executeConfigRequiresPermissionAndShowsCurrentConfig() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommandService service = new DataProviderCommandService(handler);
        RecordingOutput output = new RecordingOutput();

        service.execute(new String[]{"config"}, deniedPermissions(), output::record);
        assertTrue(output.hasMessageContaining("Missing permission: dataprovider.command.config"));

        when(handler.getConfiguredDatabaseTypeStates()).thenReturn(Map.of(
                DatabaseType.MYSQL, true,
                DatabaseType.MONGODB, false,
                DatabaseType.REDIS, true,
                DatabaseType.REDIS_MESSAGING, true
        ));
        when(handler.getConfiguredOrmSchemaMode()).thenReturn("update");

        output.clear();
        service.execute(
                new String[]{"config"},
                permissions("dataprovider.command.config"),
                output::record
        );

        assertTrue(output.hasMessageContaining("DataProvider Config"));
        assertTrue(output.hasMessageContaining("ORM schema_mode=update"));
        assertTrue(output.hasMessageContaining("MYSQL: enabled"));
        assertTrue(output.hasMessageContaining("MONGODB: disabled"));
    }

    @Test
    void executeReloadRequiresPermissionAndDelegatesToHandler() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommandService service = new DataProviderCommandService(handler);
        RecordingOutput output = new RecordingOutput();

        service.execute(new String[]{"reload"}, deniedPermissions(), output::record);
        assertTrue(output.hasMessageContaining("Missing permission: dataprovider.command.reload"));

        output.clear();
        service.execute(
                new String[]{"reload"},
                permissions("dataprovider.command.reload"),
                output::record
        );

        verify(handler).reloadConfiguration();
        assertTrue(output.hasMessageContaining("Reloaded DataProvider configuration from disk."));
    }

    @Test
    void executeUnknownSubcommandShowsError() {
        DataProviderCommandService service = new DataProviderCommandService(mock(DataProviderHandler.class));
        RecordingOutput output = new RecordingOutput();

        service.execute(new String[]{"unknown"}, permissions("dataprovider.command.status"), output::record);

        assertTrue(output.hasMessageContaining("Unknown subcommand"));
    }

    @Test
    void suggestRespectsPermissionsAndSupportsStatusArguments() {
        DataProviderHandler handler = mock(DataProviderHandler.class);
        DataProviderCommandService service = new DataProviderCommandService(handler);

        DatabaseConnectionKey keyA = new DatabaseConnectionKey("AlphaPlugin", DatabaseType.MYSQL, "main");
        DatabaseConnectionKey keyB = new DatabaseConnectionKey("BetaPlugin", DatabaseType.REDIS, "cache");
        ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> activeDatabases = new ConcurrentHashMap<>();
        activeDatabases.put(keyA, connectedProvider());
        activeDatabases.put(keyB, connectedProvider());
        when(handler.getActiveDatabases()).thenReturn(activeDatabases);

        assertEquals(List.of("help"), service.suggest(new String[]{""}, deniedPermissions()));
        assertEquals(List.of("help", "status"), service.suggest(new String[]{""}, permissions("dataprovider.command.status")));

        Predicate<String> allPermissions = permissions(
                "dataprovider.command.status",
                "dataprovider.command.config",
                "dataprovider.command.reload"
        );
        assertEquals(List.of("help", "status", "config", "reload"), service.suggest(new String[]{""}, allPermissions));
        assertEquals(List.of("summary"), service.suggest(new String[]{"status", "s"}, permissions("dataprovider.command.status")));
        assertEquals(List.of("AlphaPlugin"), service.suggest(new String[]{"status", "plugin", "A"}, permissions("dataprovider.command.status")));
        assertEquals(List.of("redis", "redis_messaging"), service.suggest(new String[]{"status", "type", "r"}, permissions("dataprovider.command.status")));
    }

    private static DatabaseProvider connectedProvider() {
        DatabaseProvider provider = mock(DatabaseProvider.class);
        when(provider.isConnected()).thenReturn(true);
        return provider;
    }

    private static DatabaseProvider disconnectedProvider() {
        DatabaseProvider provider = mock(DatabaseProvider.class);
        when(provider.isConnected()).thenReturn(false);
        return provider;
    }

    private static Predicate<String> deniedPermissions() {
        return permission -> false;
    }

    private static Predicate<String> permissions(String... grantedPermissions) {
        Set<String> granted = Set.of(grantedPermissions);
        return granted::contains;
    }

    private static final class RecordingOutput {
        private final List<String> messages = new ArrayList<>();

        private void record(Component message) {
            messages.add(PlainTextComponentSerializer.plainText().serialize(message));
        }

        private boolean hasMessageContaining(String fragment) {
            return messages.stream().anyMatch(text -> text.contains(fragment));
        }

        private void clear() {
            messages.clear();
        }
    }
}
