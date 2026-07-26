package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.api.DataProviderScope;
import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.exception.DataProviderFailureContext;
import nl.hauntedmc.dataprovider.exception.ExecutionOutcome;
import nl.hauntedmc.dataprovider.exception.ProviderClosedException;
import nl.hauntedmc.dataprovider.exception.RetryAdvice;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Optional scoped lifecycle helper for independently managed plugin components. */
public final class DefaultDataProviderScope implements DataProviderScope {

    private static final String CLOSED_MESSAGE = "DataProvider scope is closed.";

    private final DataProviderHandler handler;
    private final OwnerScope ownerScope;
    private final OwnerScope registrationScope;
    private final PluginIdentity identity;
    private final Object lifecycleMonitor = new Object();
    private volatile LifecycleState lifecycleState = LifecycleState.OPEN;

    DefaultDataProviderScope(DataProviderHandler handler, OwnerScope ownerScope, PluginIdentity identity) {
        this.handler = Objects.requireNonNull(handler, "DataProviderHandler cannot be null.");
        this.ownerScope = Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        this.registrationScope = uniqueRegistrationScope(ownerScope);
        this.identity = Objects.requireNonNull(identity, "Plugin identity cannot be null.");
    }

    @Override
    public OwnerScope ownerScope() {
        requireOwner();
        return ownerScope;
    }

    @Override
    public LifecycleState lifecycleState() {
        requireOwner();
        return lifecycleState;
    }

    @Override
    public DatabaseProvider registerDatabaseOrThrow(DatabaseType databaseType, String connectionIdentifier) {
        synchronized (lifecycleMonitor) {
            requireStructuredOpen("scope.registerDatabase");
            return DefaultDataProviderApi.wrapProvider(handler, identity,
                    handler.registerDatabaseForScopeOrThrow(
                            identity,
                            registrationScope,
                            databaseType,
                            connectionIdentifier
                    )
            );
        }
    }

    @Override
    public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        synchronized (lifecycleMonitor) {
            requireOpen("scope.unregisterDatabase");
            handler.unregisterDatabaseForScope(identity, registrationScope, databaseType, connectionIdentifier);
        }
    }

    @Override
    public void unregisterAllDatabases() {
        synchronized (lifecycleMonitor) {
            requireOpen("scope.unregisterAllDatabases");
            handler.unregisterAllDatabasesForScope(identity, registrationScope);
        }
    }

    @Override
    public DatabaseProvider requireRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        synchronized (lifecycleMonitor) {
            requireStructuredOpen("scope.requireRegisteredDatabase");
            return DefaultDataProviderApi.wrapProvider(handler, identity,
                    handler.requireRegisteredDatabaseForScope(
                            identity,
                            registrationScope,
                            databaseType,
                            connectionIdentifier
                    )
            );
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleMonitor) {
            requireOwner();
            if (lifecycleState != LifecycleState.OPEN) {
                return;
            }
            lifecycleState = LifecycleState.CLOSING;
            try {
                handler.unregisterAllDatabasesForScope(identity, registrationScope);
                lifecycleState = LifecycleState.CLOSED;
            } catch (RuntimeException | Error failure) {
                lifecycleState = LifecycleState.OPEN;
                throw failure;
            }
        }
    }

    private void requireStructuredOpen(String operation) {
        requireOwner();
        if (lifecycleState != LifecycleState.OPEN) {
            throw new ProviderClosedException(
                    CLOSED_MESSAGE,
                    DataProviderFailureContext.of(
                            null,
                            null,
                            operation,
                            RetryAdvice.NEVER,
                            ExecutionOutcome.NOT_STARTED
                    ).withDiagnostics(Map.of("ownerScope", ownerScope.value())),
                    null
            );
        }
    }

    private void requireOpen(String operation) {
        requireStructuredOpen(operation);
    }

    private void requireOwner() {
        handler.requireIdentity(identity);
    }

    private static OwnerScope uniqueRegistrationScope(OwnerScope ownerScope) {
        String suffix = "$" + UUID.randomUUID();
        String owner = ownerScope.value();
        int maximumPrefixLength = 256 - suffix.length();
        return OwnerScope.of(owner.substring(0, Math.min(owner.length(), maximumPrefixLength)) + suffix);
    }
}
