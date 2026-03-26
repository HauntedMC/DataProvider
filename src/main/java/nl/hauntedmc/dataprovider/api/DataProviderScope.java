package nl.hauntedmc.dataprovider.api;

import nl.hauntedmc.dataprovider.database.DataAccess;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Optional scoped lifecycle helper for advanced integrations that need isolated ownership domains
 * inside one plugin/software process.
 *
 * Typical use:
 * - create one scope per logical component
 * - register and use connections through this scope
 * - release only this scope via {@link #unregisterAllDatabases()} or {@link #close()}
 */
public final class DataProviderScope implements AutoCloseable {

    private static final Pattern OWNER_SCOPE_PATTERN = Pattern.compile("[A-Za-z0-9_.:$-]{1,256}");

    private final DataProviderHandler handler;
    private final String ownerScope;

    DataProviderScope(DataProviderHandler handler, String ownerScope) {
        this.handler = Objects.requireNonNull(handler, "DataProviderHandler cannot be null.");
        this.ownerScope = validateOwnerScope(ownerScope);
    }

    /**
     * Returns the normalized scope identifier used for ownership tracking.
     */
    public String ownerScope() {
        return ownerScope;
    }

    /**
     * Registers a database connection under this scope.
     */
    public DatabaseProvider registerDatabase(DatabaseType databaseType, String connectionIdentifier) {
        return DataProviderAPI.wrapProvider(handler.registerDatabaseForScope(ownerScope, databaseType, connectionIdentifier));
    }

    /**
     * Registers a database connection under this scope and returns Optional.
     */
    public Optional<DatabaseProvider> registerDatabaseOptional(DatabaseType databaseType, String connectionIdentifier) {
        return Optional.ofNullable(registerDatabase(databaseType, connectionIdentifier));
    }

    /**
     * Registers and casts the scoped provider to the expected type.
     */
    public <T extends DatabaseProvider> Optional<T> registerDatabaseAs(
            DatabaseType databaseType,
            String connectionIdentifier,
            Class<T> expectedProviderType
    ) {
        return DataProviderAPI.castProvider(registerDatabase(databaseType, connectionIdentifier), expectedProviderType);
    }

    /**
     * Registers and resolves typed data access from the scoped provider.
     */
    public <T extends DataAccess> Optional<T> registerDataAccess(
            DatabaseType databaseType,
            String connectionIdentifier,
            Class<T> expectedDataAccessType
    ) {
        Objects.requireNonNull(expectedDataAccessType, "Expected data access type cannot be null.");
        return registerDatabaseOptional(databaseType, connectionIdentifier)
                .flatMap(provider -> provider.getDataAccessOptional(expectedDataAccessType));
    }

    /**
     * Releases one scoped registration reference.
     */
    public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        handler.unregisterDatabaseForScope(ownerScope, databaseType, connectionIdentifier);
    }

    /**
     * Releases all registrations held by this scope.
     */
    public void unregisterAllDatabases() {
        handler.unregisterAllDatabasesForScope(ownerScope);
    }

    @Override
    public void close() {
        unregisterAllDatabases();
    }

    private static String validateOwnerScope(String ownerScope) {
        if (ownerScope == null || ownerScope.isBlank()) {
            throw new IllegalArgumentException("Owner scope cannot be null or blank.");
        }
        String normalized = ownerScope.trim();
        if (!OWNER_SCOPE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Owner scope contains unsupported characters.");
        }
        return normalized;
    }
}
