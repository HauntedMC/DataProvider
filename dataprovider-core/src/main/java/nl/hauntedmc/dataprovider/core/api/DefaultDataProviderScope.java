package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.api.DataProviderScope;
import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.api.observation.DataProviderObserver;
import nl.hauntedmc.dataprovider.api.observation.DataProviderOperationContext;
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
    private final DataProviderObserver observer;
    private final String pluginId;
    private final Object lifecycleMonitor = new Object();
    private volatile LifecycleState lifecycleState = LifecycleState.OPEN;

    DefaultDataProviderScope(DataProviderHandler handler, OwnerScope ownerScope, PluginIdentity identity) {
        this(handler, ownerScope, identity, DataProviderObserver.noop(), identity.pluginId());
    }

    DefaultDataProviderScope(
            DataProviderHandler handler,
            OwnerScope ownerScope,
            PluginIdentity identity,
            DataProviderObserver observer,
            String pluginId
    ) {
        this.handler = Objects.requireNonNull(handler, "DataProviderHandler cannot be null.");
        this.ownerScope = Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        this.registrationScope = uniqueRegistrationScope(ownerScope);
        this.identity = Objects.requireNonNull(identity, "Plugin identity cannot be null.");
        this.observer = Objects.requireNonNull(observer, "DataProvider observer cannot be null.");
        this.pluginId = Objects.requireNonNull(pluginId, "Plugin id cannot be null.");
    }

    @Override
    public OwnerScope ownerScope() {
        requireCleanupOwner();
        return ownerScope;
    }

    @Override
    public LifecycleState lifecycleState() {
        requireCleanupOwner();
        return lifecycleState;
    }

    @Override
    public DatabaseProvider registerDatabaseOrThrow(DatabaseType databaseType, String connectionIdentifier) {
        synchronized (lifecycleMonitor) {
            requireStructuredOpen("scope.registerDatabase");
            if (!DataProviderObservations.isEnabled(observer)) {
                return registerAndWrap(databaseType, connectionIdentifier);
            }
            return DataProviderObservations.observe(
                    observer,
                    operationContext(databaseType, "database.register"),
                    () -> registerAndWrap(databaseType, connectionIdentifier)
            );
        }
    }

    @Override
    public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        synchronized (lifecycleMonitor) {
            requireCleanupOpen("scope.unregisterDatabase");
            if (!DataProviderObservations.isEnabled(observer)) {
                handler.unregisterDatabaseForScope(identity, registrationScope, databaseType, connectionIdentifier);
                return;
            }
            DataProviderObservations.observe(
                    observer,
                    operationContext(databaseType, "database.unregister"),
                    () -> handler.unregisterDatabaseForScope(
                            identity,
                            registrationScope,
                            databaseType,
                            connectionIdentifier
                    )
            );
        }
    }

    @Override
    public void unregisterAllDatabases() {
        synchronized (lifecycleMonitor) {
            requireCleanupOpen("scope.unregisterAllDatabases");
            handler.unregisterAllDatabasesForScope(identity, registrationScope);
        }
    }

    @Override
    public DatabaseProvider requireRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        synchronized (lifecycleMonitor) {
            requireStructuredOpen("scope.requireRegisteredDatabase");
            return DefaultDataProviderApi.wrapProvider(
                    handler,
                    identity,
                    handler.requireRegisteredDatabaseForScope(
                            identity,
                            registrationScope,
                            databaseType,
                            connectionIdentifier
                    ),
                    observer,
                    pluginId,
                    ownerScope,
                    databaseType
            );
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleMonitor) {
            requireCleanupOwner();
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

    private DatabaseProvider registerAndWrap(DatabaseType databaseType, String connectionIdentifier) {
        return DefaultDataProviderApi.wrapProvider(
                handler,
                identity,
                handler.registerDatabaseForScopeOrThrow(
                        identity,
                        registrationScope,
                        databaseType,
                        connectionIdentifier
                ),
                observer,
                pluginId,
                ownerScope,
                databaseType
        );
    }

    private DataProviderOperationContext operationContext(DatabaseType databaseType, String operation) {
        return new DataProviderOperationContext(pluginId, ownerScope, databaseType, operation);
    }

    private void requireStructuredOpen(String operation) {
        requireOwner();
        requireLocallyOpen(operation);
    }

    private void requireCleanupOpen(String operation) {
        requireCleanupOwner();
        requireLocallyOpen(operation);
    }

    private void requireLocallyOpen(String operation) {
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

    private void requireOwner() {
        handler.requireIdentity(identity);
    }

    private void requireCleanupOwner() {
        handler.requireIdentityForCleanup(identity);
    }

    private static OwnerScope uniqueRegistrationScope(OwnerScope ownerScope) {
        String suffix = "$" + UUID.randomUUID();
        String owner = ownerScope.value();
        int maximumPrefixLength = 256 - suffix.length();
        return OwnerScope.of(owner.substring(0, Math.min(owner.length(), maximumPrefixLength)) + suffix);
    }
}
