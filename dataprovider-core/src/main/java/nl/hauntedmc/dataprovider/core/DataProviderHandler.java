package nl.hauntedmc.dataprovider.core;

import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.core.concurrent.DataProviderExecutionRuntime;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionRuntimeConfig;
import nl.hauntedmc.dataprovider.core.config.ConfigHandler;
import nl.hauntedmc.dataprovider.core.exception.DataProviderExceptionMapper;
import nl.hauntedmc.dataprovider.core.identity.CallerContext;
import nl.hauntedmc.dataprovider.core.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.core.identity.StackCallerClassLoaderResolver;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.exception.DataProviderFailureContext;
import nl.hauntedmc.dataprovider.exception.DataProviderRegistrationException;
import nl.hauntedmc.dataprovider.exception.ExecutionOutcome;
import nl.hauntedmc.dataprovider.exception.ProviderClosedException;
import nl.hauntedmc.dataprovider.exception.RetryAdvice;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

/** Public entry point for plugin-scoped database operations. */
public class DataProviderHandler {

    private static final String INTERNAL_PACKAGE_PREFIX = "nl.hauntedmc.dataprovider.core";
    private static final String CLOSED_MESSAGE =
            "DataProvider API is no longer available. Obtain a fresh API instance after plugin enable.";

    private final DataProviderRegistry registry;
    private final CallerContextResolver callerContextResolver;
    private final LoggerAdapter logger;
    private final ClassLoader ownClassLoader;
    private final DataProviderExecutionRuntime executionRuntime;

    public DataProviderHandler(
            Path dataPath,
            ClassLoader resourceClassLoader,
            ConfigHandler configHandler,
            CallerContextResolver callerContextResolver,
            LoggerAdapter logger
    ) {
        Objects.requireNonNull(dataPath, "Data path cannot be null.");
        Objects.requireNonNull(resourceClassLoader, "Resource class loader cannot be null.");
        Objects.requireNonNull(configHandler, "Config handler cannot be null.");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
        this.callerContextResolver = Objects.requireNonNull(callerContextResolver,
                "Caller context resolver cannot be null.");
        ownClassLoader = resourceClassLoader;
        DatabaseConfigMap configMap = new DatabaseConfigMap(dataPath, this.logger, resourceClassLoader);
        executionRuntime = new DataProviderExecutionRuntime(ExecutionRuntimeConfig.from(configHandler.getConfig()));
        DatabaseFactory factory = new DatabaseFactory(configMap, this.logger, executionRuntime);
        registry = new DataProviderRegistry(factory, configHandler, this.logger);
    }

    DataProviderHandler(
            DataProviderRegistry registry,
            CallerContextResolver callerContextResolver,
            LoggerAdapter logger,
            ClassLoader ownClassLoader
    ) {
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
        this.registry = Objects.requireNonNull(registry, "Registry cannot be null.");
        this.callerContextResolver = Objects.requireNonNull(callerContextResolver,
                "Caller context resolver cannot be null.");
        this.ownClassLoader = Objects.requireNonNull(ownClassLoader, "Own class loader cannot be null.");
        executionRuntime = null;
    }

    public DatabaseProvider registerDatabase(DatabaseType databaseType, String connectionIdentifier) {
        requireOpen();
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        PluginId pluginId = PluginId.of(resolveCallerContext().pluginId());
        ConnectionIdentifier identifier = ConnectionIdentifier.of(connectionIdentifier);
        return registerLegacy(pluginId, OwnerScopeId.of(pluginId.value()), databaseType, identifier);
    }

    public DatabaseProvider registerDatabaseOrThrow(DatabaseType databaseType, String connectionIdentifier) {
        requireOpen();
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        PluginId pluginId = PluginId.of(resolveCallerContext().pluginId());
        ConnectionIdentifier identifier = ConnectionIdentifier.of(connectionIdentifier);
        return registerStrict(pluginId, OwnerScopeId.of(pluginId.value()), databaseType, identifier,
                "registerDatabase");
    }

    public DatabaseProvider registerDatabaseForScope(
            String ownerScope,
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        return registerDatabaseForScope(OwnerScope.of(ownerScope), databaseType, connectionIdentifier);
    }

    public DatabaseProvider registerDatabaseForScope(
            OwnerScope ownerScope,
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        requireOpen();
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        PluginId pluginId = PluginId.of(resolveCallerContext().pluginId());
        return registerLegacy(pluginId, OwnerScopeId.from(ownerScope), databaseType,
                ConnectionIdentifier.of(connectionIdentifier));
    }

    public DatabaseProvider registerDatabaseForScopeOrThrow(
            OwnerScope ownerScope,
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        requireOpen();
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        PluginId pluginId = PluginId.of(resolveCallerContext().pluginId());
        return registerStrict(pluginId, OwnerScopeId.from(ownerScope), databaseType,
                ConnectionIdentifier.of(connectionIdentifier), "scope.registerDatabase");
    }

    private DatabaseProvider registerLegacy(
            PluginId pluginId,
            OwnerScopeId ownerScope,
            DatabaseType type,
            ConnectionIdentifier identifier
    ) {
        return DatabaseFactory.withCreationPlugin(pluginId,
                () -> registry.registerDatabase(pluginId, ownerScope, type, identifier));
    }

    private DatabaseProvider registerStrict(
            PluginId pluginId,
            OwnerScopeId ownerScope,
            DatabaseType type,
            ConnectionIdentifier identifier,
            String operation
    ) {
        DatabaseProvider provider = registerLegacy(pluginId, ownerScope, type, identifier);
        if (provider != null) {
            return provider;
        }
        if (!registry.getConfiguredDatabaseTypeStates().getOrDefault(type, true)) {
            throw DataProviderExceptionMapper.backendDisabled(type, identifier.value());
        }
        DatabaseConnectionKey key = new DatabaseConnectionKey(pluginId.value(), type, identifier.value());
        ProviderLifecycleSnapshot snapshot = registry.getProviderLifecycleSnapshots().get(key);
        Throwable failure = snapshot == null ? null : snapshot.failure();
        throw DataProviderExceptionMapper.registrationFailure(failure, type, identifier.value(), operation);
    }

    public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        requireOpen();
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        PluginId pluginId = PluginId.of(resolveCallerContext().pluginId());
        registry.unregisterDatabase(pluginId, OwnerScopeId.of(pluginId.value()), databaseType,
                ConnectionIdentifier.of(connectionIdentifier));
    }

    public void unregisterDatabaseForScope(String ownerScope, DatabaseType databaseType, String connectionIdentifier) {
        unregisterDatabaseForScope(OwnerScope.of(ownerScope), databaseType, connectionIdentifier);
    }

    public void unregisterDatabaseForScope(
            OwnerScope ownerScope,
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        requireOpen();
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        PluginId pluginId = PluginId.of(resolveCallerContext().pluginId());
        registry.unregisterDatabase(pluginId, OwnerScopeId.from(ownerScope), databaseType,
                ConnectionIdentifier.of(connectionIdentifier));
    }

    public void unregisterAllDatabases() {
        requireOpen();
        PluginId pluginId = PluginId.of(resolveCallerContext().pluginId());
        registry.unregisterAllDatabases(pluginId, OwnerScopeId.of(pluginId.value()));
    }

    public void unregisterAllDatabasesForScope(String ownerScope) {
        unregisterAllDatabasesForScope(OwnerScope.of(ownerScope));
    }

    public void unregisterAllDatabasesForScope(OwnerScope ownerScope) {
        requireOpen();
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        PluginId pluginId = PluginId.of(resolveCallerContext().pluginId());
        registry.unregisterAllDatabases(pluginId, OwnerScopeId.from(ownerScope));
    }

    public void unregisterAllDatabasesForPlugin() {
        requireOpen();
        registry.unregisterAllDatabasesForPlugin(PluginId.of(resolveCallerContext().pluginId()));
    }

    public void shutdownAllDatabases() {
        requireInternalCaller();
        try {
            registry.shutdownAllDatabases();
        } finally {
            if (executionRuntime != null) {
                executionRuntime.close();
            }
        }
    }

    public DatabaseProvider getRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        requireOpen();
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        return registry.getDatabase(PluginId.of(resolveCallerContext().pluginId()), databaseType,
                ConnectionIdentifier.of(connectionIdentifier));
    }

    public DatabaseProvider requireRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        DatabaseProvider provider = getRegisteredDatabase(databaseType, connectionIdentifier);
        if (provider != null) {
            return provider;
        }
        throw missingRegistration(databaseType, connectionIdentifier, "requireRegisteredDatabase");
    }

    public DatabaseProvider getRegisteredDatabaseForScope(
            OwnerScope ownerScope,
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        requireOpen();
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        return registry.getDatabase(PluginId.of(resolveCallerContext().pluginId()), OwnerScopeId.from(ownerScope),
                databaseType, ConnectionIdentifier.of(connectionIdentifier));
    }

    public DatabaseProvider requireRegisteredDatabaseForScope(
            OwnerScope ownerScope,
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        DatabaseProvider provider = getRegisteredDatabaseForScope(ownerScope, databaseType, connectionIdentifier);
        if (provider != null) {
            return provider;
        }
        throw missingRegistration(databaseType, connectionIdentifier, "scope.requireRegisteredDatabase");
    }

    private DataProviderRegistrationException missingRegistration(
            DatabaseType type, String identifier, String operation) {
        return new DataProviderRegistrationException(
                "No active database registration exists for the requested connection.",
                DataProviderFailureContext.of(type, identifier, operation, RetryAdvice.NEVER,
                        ExecutionOutcome.NOT_STARTED), null);
    }

    public ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> getActiveDatabases() {
        requireOpen();
        requireInternalCaller();
        return registry.getActiveDatabases();
    }

    public Map<DatabaseConnectionKey, Integer> getActiveDatabaseReferenceCounts() {
        requireOpen();
        requireInternalCaller();
        return registry.getActiveDatabaseReferenceCounts();
    }

    public Map<DatabaseConnectionKey, ConnectionHealthSnapshot> getCachedDatabaseHealth() {
        requireOpen();
        requireInternalCaller();
        return registry.getCachedHealthSnapshots();
    }

    public CompletableFuture<Void> probeDatabaseHealthAsync() {
        requireOpen();
        requireInternalCaller();
        return registry.probeRemoteHealthAsync();
    }

    public Map<DatabaseType, Boolean> getConfiguredDatabaseTypeStates() {
        requireOpen();
        requireInternalCaller();
        return registry.getConfiguredDatabaseTypeStates();
    }

    public String getConfiguredOrmSchemaMode() {
        requireOpen();
        requireInternalCaller();
        return registry.getOrmSchemaMode();
    }

    public void reloadConfiguration() {
        requireOpen();
        requireInternalCaller();
        try {
            registry.reloadConfiguration();
        } catch (RuntimeException failure) {
            throw DataProviderExceptionMapper.configurationFailure(failure, "reloadConfiguration");
        }
    }

    private CallerContext resolveCallerContext() {
        CallerContext caller = callerContextResolver.resolveCaller();
        if (caller == null) {
            logger.error("Could not resolve caller plugin context for API operation.");
            throw new SecurityException("Could not resolve caller plugin context.");
        }
        return caller;
    }

    private void requireInternalCaller() {
        ClassLoader callerLoader = StackCallerClassLoaderResolver.resolveNearestCallerOutsidePackage(
                INTERNAL_PACKAGE_PREFIX);
        if (callerLoader == null || callerLoader != ownClassLoader) {
            logger.error("Rejected privileged operation from non-internal caller.");
            throw new SecurityException("Privileged DataProvider operation is restricted to internal callers.");
        }
    }

    private void requireOpen() {
        if (registry.isClosed()) {
            throw new ProviderClosedException(
                    CLOSED_MESSAGE,
                    DataProviderFailureContext.of(null, null, "api", RetryAdvice.NEVER,
                            ExecutionOutcome.NOT_STARTED), null);
        }
    }
}
