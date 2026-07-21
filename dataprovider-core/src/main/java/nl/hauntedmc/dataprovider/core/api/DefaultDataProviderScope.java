package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.api.DataProviderScope;
import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.exception.DataProviderFailureContext;
import nl.hauntedmc.dataprovider.exception.ExecutionOutcome;
import nl.hauntedmc.dataprovider.exception.ProviderClosedException;
import nl.hauntedmc.dataprovider.exception.RetryAdvice;

import java.util.Objects;

/** Optional scoped lifecycle helper for independently managed plugin components. */
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

    @Override
    public OwnerScope ownerScope() {
        return ownerScope;
    }

    @Override
    public LifecycleState lifecycleState() {
        return lifecycleState;
    }

    @Override
    public DatabaseProvider registerDatabase(DatabaseType databaseType, String connectionIdentifier) {
        synchronized (lifecycleMonitor) {
            requireOpen();
            return DefaultDataProviderApi.wrapProvider(
                    handler.registerDatabaseForScope(ownerScope, databaseType, connectionIdentifier));
        }
    }

    @Override
    public DatabaseProvider registerDatabaseOrThrow(DatabaseType databaseType, String connectionIdentifier) {
        synchronized (lifecycleMonitor) {
            requireOpen();
            return DefaultDataProviderApi.wrapProvider(
                    handler.registerDatabaseForScopeOrThrow(ownerScope, databaseType, connectionIdentifier));
        }
    }

    @Override
    public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        synchronized (lifecycleMonitor) {
            requireOpen();
            handler.unregisterDatabaseForScope(ownerScope, databaseType, connectionIdentifier);
        }
    }

    @Override
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
                    handler.getRegisteredDatabaseForScope(ownerScope, databaseType, connectionIdentifier));
        }
    }

    @Override
    public DatabaseProvider requireRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        synchronized (lifecycleMonitor) {
            requireOpen();
            return DefaultDataProviderApi.wrapProvider(
                    handler.requireRegisteredDatabaseForScope(ownerScope, databaseType, connectionIdentifier));
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
            throw new ProviderClosedException(
                    CLOSED_MESSAGE,
                    DataProviderFailureContext.of(null, null, "scope", RetryAdvice.NEVER,
                            ExecutionOutcome.NOT_STARTED)
                            .withDiagnostics(java.util.Map.of("ownerScope", ownerScope.value())),
                    null
            );
        }
    }
}
