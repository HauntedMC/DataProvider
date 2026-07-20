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

    OwnerScope ownerScope();

    DatabaseProvider registerDatabase(DatabaseType databaseType, String connectionIdentifier);

    void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier);

    void unregisterAllDatabases();

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

    @Override
    default void close() {
        unregisterAllDatabases();
    }
}
