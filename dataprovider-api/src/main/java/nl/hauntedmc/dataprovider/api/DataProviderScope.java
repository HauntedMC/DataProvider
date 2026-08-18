package nl.hauntedmc.dataprovider.api;

import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;

import java.util.Objects;

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

    /**
     * Registers a database and casts the returned handle to the expected backend-specific provider type.
     */
    default <T extends DatabaseProvider> T registerDatabaseOrThrow(
            DatabaseType databaseType,
            String connectionIdentifier,
            Class<T> providerType
    ) {
        Objects.requireNonNull(providerType, "Provider type cannot be null.");
        return providerType.cast(registerDatabaseOrThrow(databaseType, connectionIdentifier));
    }

    void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier);

    void unregisterAllDatabases();

    DatabaseProvider requireRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier);

    /**
     * Returns a registered provider cast to the expected backend-specific provider type.
     */
    default <T extends DatabaseProvider> T requireRegisteredDatabase(
            DatabaseType databaseType,
            String connectionIdentifier,
            Class<T> providerType
    ) {
        Objects.requireNonNull(providerType, "Provider type cannot be null.");
        return providerType.cast(requireRegisteredDatabase(databaseType, connectionIdentifier));
    }

    @Override
    default void close() {
        unregisterAllDatabases();
    }
}
