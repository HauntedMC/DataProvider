package nl.hauntedmc.dataprovider.api;

import nl.hauntedmc.dataprovider.database.DataAccess;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.exception.DataProviderFailureContext;
import nl.hauntedmc.dataprovider.exception.DataProviderRegistrationException;
import nl.hauntedmc.dataprovider.exception.ExecutionOutcome;
import nl.hauntedmc.dataprovider.exception.RetryAdvice;

import java.util.Objects;
import java.util.Optional;

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

    DatabaseProvider registerDatabase(DatabaseType databaseType, String connectionIdentifier);

    default DatabaseProvider registerDatabaseOrThrow(DatabaseType databaseType, String connectionIdentifier) {
        DatabaseProvider provider = registerDatabase(databaseType, connectionIdentifier);
        if (provider != null) {
            return provider;
        }
        throw new DataProviderRegistrationException(
                "Scoped database registration failed.",
                DataProviderFailureContext.of(
                        databaseType,
                        connectionIdentifier,
                        "scope.registerDatabase",
                        RetryAdvice.CONDITIONAL,
                        ExecutionOutcome.NOT_STARTED
                ).withDiagnostics(java.util.Map.of("ownerScope", ownerScope().value())),
                null
        );
    }

    void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier);

    void unregisterAllDatabases();

    default DatabaseProvider getRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        throw new UnsupportedOperationException("Scoped provider lookup is not supported by this implementation.");
    }

    default DatabaseProvider requireRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        DatabaseProvider provider = getRegisteredDatabase(databaseType, connectionIdentifier);
        if (provider != null) {
            return provider;
        }
        throw new DataProviderRegistrationException(
                "No active scoped database registration exists for the requested connection.",
                DataProviderFailureContext.of(
                        databaseType,
                        connectionIdentifier,
                        "scope.requireRegisteredDatabase",
                        RetryAdvice.NEVER,
                        ExecutionOutcome.NOT_STARTED
                ).withDiagnostics(java.util.Map.of("ownerScope", ownerScope().value())),
                null
        );
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
