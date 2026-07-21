package nl.hauntedmc.dataprovider.api;

import nl.hauntedmc.dataprovider.api.orm.ORMContext;
import nl.hauntedmc.dataprovider.database.DataAccess;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.exception.DataProviderFailureContext;
import nl.hauntedmc.dataprovider.exception.DataProviderRegistrationException;
import nl.hauntedmc.dataprovider.exception.ExecutionOutcome;
import nl.hauntedmc.dataprovider.exception.RetryAdvice;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.Optional;

/** Public, platform-neutral facade for plugin-scoped database registrations. */
public interface DataProviderAPI {

    ORMContext createOrmContext(
            String pluginName,
            DataSource dataSource,
            LoggerAdapter logger,
            String schemaMode,
            Class<?>... entityClasses
    );

    /** Legacy nullable registration method retained for compatibility. */
    DatabaseProvider registerDatabase(DatabaseType databaseType, String connectionIdentifier);

    /**
     * Registers a database or throws a structured public exception retaining the failure category.
     * Implementations should override this method to preserve backend-specific failure details.
     */
    default DatabaseProvider registerDatabaseOrThrow(DatabaseType databaseType, String connectionIdentifier) {
        DatabaseProvider provider = registerDatabase(databaseType, connectionIdentifier);
        if (provider != null) {
            return provider;
        }
        throw new DataProviderRegistrationException(
                "Database registration failed.",
                DataProviderFailureContext.of(
                        databaseType,
                        connectionIdentifier,
                        "registerDatabase",
                        RetryAdvice.CONDITIONAL,
                        ExecutionOutcome.NOT_STARTED
                ),
                null
        );
    }

    DataProviderScope scope(OwnerScope ownerScope);

    void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier);

    void unregisterAllDatabases();

    void unregisterAllDatabasesForPlugin();

    /** Legacy nullable lookup retained for compatibility. */
    DatabaseProvider getRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier);

    /** Returns a registered provider or throws a structured registration-state failure. */
    default DatabaseProvider requireRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        DatabaseProvider provider = getRegisteredDatabase(databaseType, connectionIdentifier);
        if (provider != null) {
            return provider;
        }
        throw new DataProviderRegistrationException(
                "No active database registration exists for the requested connection.",
                DataProviderFailureContext.of(
                        databaseType,
                        connectionIdentifier,
                        "requireRegisteredDatabase",
                        RetryAdvice.NEVER,
                        ExecutionOutcome.NOT_STARTED
                ),
                null
        );
    }

    default DataProviderScope scope(String ownerScope) {
        return scope(OwnerScope.of(ownerScope));
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
        return castProvider(registerDatabase(databaseType, connectionIdentifier), expectedProviderType);
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
        return castProvider(getRegisteredDatabase(databaseType, connectionIdentifier), expectedProviderType);
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

    private static <T extends DatabaseProvider> Optional<T> castProvider(
            DatabaseProvider provider,
            Class<T> expectedProviderType
    ) {
        Objects.requireNonNull(expectedProviderType, "Expected provider type cannot be null.");
        if (provider == null || !expectedProviderType.isInstance(provider)) {
            return Optional.empty();
        }
        return Optional.of(expectedProviderType.cast(provider));
    }
}
