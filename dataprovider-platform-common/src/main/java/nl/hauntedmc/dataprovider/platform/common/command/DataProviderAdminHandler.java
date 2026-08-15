package nl.hauntedmc.dataprovider.platform.common.command;

import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.core.resilience.ConnectionHealthSnapshot;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Bridges the internal handler to the platform-neutral administration command model. */
public final class DataProviderAdminHandler implements DataProviderAdminCommand.Handler {

    private final DataProviderHandler handler;

    public DataProviderAdminHandler(DataProviderHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler cannot be null");
    }

    @Override
    public DataProviderAdminCommand.Snapshot snapshot() {
        Map<DatabaseConnectionKey, Integer> references = handler.getActiveDatabaseReferenceCounts();
        Map<DatabaseConnectionKey, ConnectionHealthSnapshot> health = handler.getCachedDatabaseHealth();
        List<DataProviderAdminCommand.Connection> connections = handler.getActiveDatabases().keySet().stream()
                .map(key -> new DataProviderAdminCommand.Connection(
                        key.pluginName(),
                        key.type(),
                        key.connectionIdentifier(),
                        Math.max(1, references.getOrDefault(key, 1)),
                        health.getOrDefault(key, ConnectionHealthSnapshot.unprobed(false))
                ))
                .toList();
        return new DataProviderAdminCommand.Snapshot(
                connections,
                handler.getConfiguredDatabaseTypeStates(),
                handler.getConfiguredOrmSchemaMode()
        );
    }

    @Override
    public DataProviderAdminCommand.Config config() {
        return new DataProviderAdminCommand.Config(
                handler.getConfiguredDatabaseTypeStates(),
                handler.getConfiguredOrmSchemaMode()
        );
    }

    @Override
    public CompletionStage<Void> probeHealth() {
        return handler.probeDatabaseHealthAsync();
    }

    @Override
    public void reload() {
        handler.reloadConfiguration();
    }
}
