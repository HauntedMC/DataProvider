package nl.hauntedmc.dataprovider.core.api;

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
 * - release the scope's registrations via {@link #unregisterAllDatabases()} or terminate the
 * scope via {@link #close()}
 */
public final class DefaultDataProviderScope implements DataProviderScope {

    private static final String CLOSED_MESSAGE = "DataProvider scope is closed.";

    private final DataProviderHandler handler;
    private final OwnerScope ownerScope;
    private final Object lifecycleMonitor = new Object();
    private volatile LifecycleState lifecycleState = LifecycleState.OPEN;

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

    @Override
    public LifecycleState lifecycleState() {
        return lifecycleState;
    }

    /**
     * Registers a database connection under this scope.
     */
    public DatabaseProvider registerDatabase(DatabaseType databaseType, String connectionIdentifier) {
        synchronized (lifecycleMonitor) {
            requireOpen();
            return DefaultDataProviderApi.wrapProvider(
                    handler.registerDatabaseForScope(ownerScope, databaseType, connectionIdentifier)
            );
        }
    }

    /**
     * Releases one scoped registration reference.
     */
    public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        synchronized (lifecycleMonitor) {
            requireOpen();
            handler.unregisterDatabaseForScope(ownerScope, databaseType, connectionIdentifier);
        }
    }

    /**
     * Releases all registrations held by this scope.
     */
    public void unregisterAllDatabases() {
        synchronized (lifecycleMonitor) {
            requireOpen();
            handler.unregisterAllDatabasesForScope(ownerScope);
        }
    }

    @Override
    public DatabaseProvider getRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        synchronized (lifecycleMonitor) {
            requireOpen();
            return DefaultDataProviderApi.wrapProvider(
                    handler.getRegisteredDatabaseForScope(ownerScope, databaseType, connectionIdentifier)
            );
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleMonitor) {
            if (lifecycleState != LifecycleState.OPEN) {
                return;
            }
            lifecycleState = LifecycleState.CLOSING;
            try {
                handler.unregisterAllDatabasesForScope(ownerScope);
            } finally {
                lifecycleState = LifecycleState.CLOSED;
            }
        }
    }

    private void requireOpen() {
        if (lifecycleState != LifecycleState.OPEN) {
            throw new IllegalStateException(CLOSED_MESSAGE);
        }
    }
}
