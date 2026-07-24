package nl.hauntedmc.dataprovider.api;

import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;

/** Isolated lifecycle boundary for a logical component within one plugin. */
public interface DataProviderScope extends AutoCloseable {

    enum LifecycleState {
        OPEN,
        CLOSING,
        CLOSED
    }

    OwnerScope ownerScope();

    default LifecycleState lifecycleState() {
        return LifecycleState.OPEN;
    }

    DatabaseProvider registerDatabaseOrThrow(DatabaseType databaseType, String connectionIdentifier);

    void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier);

    void unregisterAllDatabases();

    DatabaseProvider requireRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier);

    @Override
    default void close() {
        unregisterAllDatabases();
    }
}
