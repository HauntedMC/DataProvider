package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.api.DataProviderScope;
import nl.hauntedmc.dataprovider.api.OwnerScope;

import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;

import java.util.Objects;

/**
 * Optional scoped lifecycle helper for advanced integrations that need isolated ownership domains
 * inside one plugin/software process.
 *
 * Typical use:
 * - create one scope per logical component
 * - register and use connections through this scope
 * - release only this scope via {@link #unregisterAllDatabases()} or {@link #close()}
 */
public final class DefaultDataProviderScope implements DataProviderScope {

    private final DataProviderHandler handler;
    private final OwnerScope ownerScope;

    DefaultDataProviderScope(DataProviderHandler handler, OwnerScope ownerScope) {
        this.handler = Objects.requireNonNull(handler, "DataProviderHandler cannot be null.");
        this.ownerScope = Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
    }

    /**
     * Returns the normalized scope identifier used for ownership tracking.
     */
    public OwnerScope ownerScope() {
        return ownerScope;
    }

    /**
     * Registers a database connection under this scope.
     */
    public DatabaseProvider registerDatabase(DatabaseType databaseType, String connectionIdentifier) {
        return DefaultDataProviderApi.wrapProvider(handler.registerDatabaseForScope(ownerScope, databaseType, connectionIdentifier));
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

}
