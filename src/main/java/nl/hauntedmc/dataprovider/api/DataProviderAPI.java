package nl.hauntedmc.dataprovider.api;

import nl.hauntedmc.dataprovider.database.DataAccess;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;

import java.util.Objects;
import java.util.Optional;

/**
 * DataProviderAPI is the public facade that exposes only the safe methods for
 * third-party plugins. Internally, it delegates to a DataProviderHandler, but it does not
 * expose sensitive methods (like shutdownAllDatabases or getActiveDatabases).
 */
public class DataProviderAPI {

    private final DataProviderHandler handler;

    /**
     * Constructs the API wrapper.
     *
     * @param handler the internal DataProviderHandler instance.
     */
    public DataProviderAPI(DataProviderHandler handler) {
        this.handler = Objects.requireNonNull(handler, "DataProviderHandler cannot be null");
    }

    /**
     * Registers a database connection for the resolved caller plugin.
     *
     * @param databaseType         the type of database (e.g. MYSQL, MONGODB, etc.)
     * @param connectionIdentifier a unique identifier for the connection
     * @return the registered {@link DatabaseProvider} instance.
     */
    public DatabaseProvider registerDatabase(DatabaseType databaseType, String connectionIdentifier) {
        return handler.registerDatabase(databaseType, connectionIdentifier);
    }

    /**
     * Registers a database connection and returns the result as Optional.
     */
    public Optional<DatabaseProvider> registerDatabaseOptional(DatabaseType databaseType, String connectionIdentifier) {
        return Optional.ofNullable(registerDatabase(databaseType, connectionIdentifier));
    }

    /**
     * Registers a database connection and casts it to an expected provider subtype.
     */
    public <T extends DatabaseProvider> Optional<T> registerDatabaseAs(
            DatabaseType databaseType,
            String connectionIdentifier,
            Class<T> expectedProviderType
    ) {
        return castProvider(registerDatabase(databaseType, connectionIdentifier), expectedProviderType);
    }

    /**
     * Registers a database connection and returns a typed data access view.
     */
    public <T extends DataAccess> Optional<T> registerDataAccess(
            DatabaseType databaseType,
            String connectionIdentifier,
            Class<T> expectedDataAccessType
    ) {
        return registerDatabaseOptional(databaseType, connectionIdentifier)
                .flatMap(provider -> provider.getDataAccessOptional(expectedDataAccessType));
    }

    /**
     * Unregisters a specific database connection for the resolved caller plugin.
     *
     * @param databaseType         the type of database.
     * @param connectionIdentifier the connection identifier.
     */
    public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        handler.unregisterDatabase(databaseType, connectionIdentifier);
    }

    /**
     * Unregisters all database connections for the resolved caller plugin.
     */
    public void unregisterAllDatabases() {
        handler.unregisterAllDatabases();
    }

    /**
     * Retrieves a registered database connection for the resolved caller plugin.
     *
     * @param databaseType         the type of database.
     * @param connectionIdentifier the connection identifier.
     * @return the {@link DatabaseProvider} instance, or null if not registered.
     */
    public DatabaseProvider getRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        return handler.getRegisteredDatabase(databaseType, connectionIdentifier);
    }

    /**
     * Retrieves a registered database connection as Optional.
     */
    public Optional<DatabaseProvider> getRegisteredDatabaseOptional(DatabaseType databaseType, String connectionIdentifier) {
        return Optional.ofNullable(getRegisteredDatabase(databaseType, connectionIdentifier));
    }

    /**
     * Retrieves a registered database connection cast to an expected provider subtype.
     */
    public <T extends DatabaseProvider> Optional<T> getRegisteredDatabaseAs(
            DatabaseType databaseType,
            String connectionIdentifier,
            Class<T> expectedProviderType
    ) {
        return castProvider(getRegisteredDatabase(databaseType, connectionIdentifier), expectedProviderType);
    }

    /**
     * Retrieves a typed data access view from a registered database connection.
     */
    public <T extends DataAccess> Optional<T> getRegisteredDataAccess(
            DatabaseType databaseType,
            String connectionIdentifier,
            Class<T> expectedDataAccessType
    ) {
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
