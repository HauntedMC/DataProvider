package nl.hauntedmc.dataprovider.api;

import nl.hauntedmc.dataprovider.database.DataAccess;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;

import java.util.Objects;
import java.util.Optional;

/**
 * Isolated lifecycle boundary for a logical component within one plugin.
 */
public interface DataProviderScope extends AutoCloseable {

    /** Lifecycle states for a scope. A closed scope cannot be reopened. */
    enum LifecycleState {
        OPEN,
        CLOSING,
        CLOSED
    }

    OwnerScope ownerScope();

    /**
     * Returns this scope's current lifecycle state.
     * Implementations created by DataProvider transition from OPEN to CLOSING to CLOSED on close.
     */
    default LifecycleState lifecycleState() {
        return LifecycleState.OPEN;
    }

    DatabaseProvider registerDatabase(DatabaseType databaseType, String connectionIdentifier);

    void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier);

    void unregisterAllDatabases();

    /**
     * Retrieves a provider registered by this scope.
     *
     * @throws UnsupportedOperationException if the scope implementation does not support scoped lookup
     */
    default DatabaseProvider getRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        throw new UnsupportedOperationException("Scoped provider lookup is not supported by this implementation.");
    }

    default Optional<DatabaseProvider> registerDatabaseOptional(
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        return Optional.ofNullable(registerDatabase(databaseType, connectionIdentifier));
    }

    default <T extends DatabaseProvider> Optional<T> registerDatabaseAs(
            DatabaseType databaseType,
            String connectionIdentifier,
            Class<T> expectedProviderType
    ) {
        Objects.requireNonNull(expectedProviderType, "Expected provider type cannot be null.");
        DatabaseProvider provider = registerDatabase(databaseType, connectionIdentifier);
        if (provider == null || !expectedProviderType.isInstance(provider)) {
            return Optional.empty();
        }
        return Optional.of(expectedProviderType.cast(provider));
    }

    default <T extends DataAccess> Optional<T> registerDataAccess(
            DatabaseType databaseType,
            String connectionIdentifier,
            Class<T> expectedDataAccessType
    ) {
        Objects.requireNonNull(expectedDataAccessType, "Expected data access type cannot be null.");
        return registerDatabaseOptional(databaseType, connectionIdentifier)
                .flatMap(provider -> provider.getDataAccessOptional(expectedDataAccessType));
    }

    default Optional<DatabaseProvider> getRegisteredDatabaseOptional(
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        return Optional.ofNullable(getRegisteredDatabase(databaseType, connectionIdentifier));
    }

    default <T extends DatabaseProvider> Optional<T> getRegisteredDatabaseAs(
            DatabaseType databaseType,
            String connectionIdentifier,
            Class<T> expectedProviderType
    ) {
        Objects.requireNonNull(expectedProviderType, "Expected provider type cannot be null.");
        DatabaseProvider provider = getRegisteredDatabase(databaseType, connectionIdentifier);
        if (provider == null || !expectedProviderType.isInstance(provider)) {
            return Optional.empty();
        }
        return Optional.of(expectedProviderType.cast(provider));
    }

    default <T extends DataAccess> Optional<T> getRegisteredDataAccess(
            DatabaseType databaseType,
            String connectionIdentifier,
            Class<T> expectedDataAccessType
    ) {
        Objects.requireNonNull(expectedDataAccessType, "Expected data access type cannot be null.");
        return getRegisteredDatabaseOptional(databaseType, connectionIdentifier)
                .flatMap(provider -> provider.getDataAccessOptional(expectedDataAccessType));
    }

    @Override
    default void close() {
        unregisterAllDatabases();
    }
}
