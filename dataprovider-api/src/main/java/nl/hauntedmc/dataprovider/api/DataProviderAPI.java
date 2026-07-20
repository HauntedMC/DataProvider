package nl.hauntedmc.dataprovider.api;

import nl.hauntedmc.dataprovider.database.DataAccess;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.api.orm.ORMContext;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.Optional;

/**
 * Public, platform-neutral facade for plugin-scoped database registrations.
 *
 * <p>The API artifact intentionally contains contracts only. Platform modules provide the
 * runtime implementation and expose an instance through their native service mechanism.</p>
 */
public interface DataProviderAPI {

    /**
     * Creates an isolated ORM context owned by the calling plugin.
     *
     * @param pluginName plugin name used for ORM diagnostics
     * @param dataSource relational data source obtained from a registered provider
     * @param logger logger that receives ORM lifecycle diagnostics
     * @param schemaMode Hibernate schema mode: validate, none, update, or create
     * @param entityClasses annotated entity classes to register
     * @return a new, initialized ORM context
     */
    ORMContext createOrmContext(
            String pluginName,
            DataSource dataSource,
            LoggerAdapter logger,
            String schemaMode,
            Class<?>... entityClasses
    );

    DatabaseProvider registerDatabase(DatabaseType databaseType, String connectionIdentifier);

    DataProviderScope scope(OwnerScope ownerScope);

    void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier);

    void unregisterAllDatabases();

    void unregisterAllDatabasesForPlugin();

    DatabaseProvider getRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier);

    /** Creates an isolated ownership scope for independently managed plugin components. */
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
