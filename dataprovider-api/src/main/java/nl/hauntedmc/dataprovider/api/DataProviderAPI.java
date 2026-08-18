package nl.hauntedmc.dataprovider.api;

import nl.hauntedmc.dataprovider.api.orm.ORMContext;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;

import javax.sql.DataSource;
import java.util.Objects;

/** Public, platform-neutral facade for plugin-scoped database registrations. */
public interface DataProviderAPI {

    /**
     * Binds this platform API gateway to the caller's platform plugin instance. Call this once
     * during plugin initialization and retain the returned facade for asynchronous work.
     */
    default DataProviderAPI forPlugin(Object platformPlugin) {
        throw new UnsupportedOperationException("This DataProvider API does not support plugin binding.");
    }

    /**
     * Creates an ORM context using the platform administrator's configured schema mode.
     *
     * <p>This is the preferred entry point for new consumers. The legacy schema-mode argument is
     * intentionally hidden because runtime configuration is authoritative.</p>
     */
    default ORMContext createConfiguredOrmContext(
            DataSource dataSource,
            LoggerAdapter logger,
            Class<?>... entityClasses
    ) {
        return createOrmContext(dataSource, logger, "validate", entityClasses);
    }

    /**
     * Creates an ORM context. The platform administrator's configured schema mode is authoritative;
     * the legacy {@code schemaMode} argument is retained for compatibility and is ignored by the
     * DataProvider runtime.
     */
    ORMContext createOrmContext(
            DataSource dataSource,
            LoggerAdapter logger,
            String schemaMode,
            Class<?>... entityClasses
    );

    /** Registers a database or throws a structured public exception retaining the failure category. */
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

    DataProviderScope scope(OwnerScope ownerScope);

    void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier);

    void unregisterAllDatabases();

    void unregisterAllDatabasesForPlugin();

    /** Returns a registered provider or throws a structured registration-state failure. */
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

    default DataProviderScope scope(String ownerScope) {
        return scope(OwnerScope.of(ownerScope));
    }

}
