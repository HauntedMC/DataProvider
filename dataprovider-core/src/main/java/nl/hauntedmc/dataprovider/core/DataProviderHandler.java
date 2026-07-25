package nl.hauntedmc.dataprovider.core;

import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.core.concurrent.DataProviderExecutionRuntime;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionRuntimeConfig;
import nl.hauntedmc.dataprovider.core.config.ConfigHandler;
import nl.hauntedmc.dataprovider.core.exception.DataProviderExceptionMapper;
import nl.hauntedmc.dataprovider.core.identity.CallerContext;
import nl.hauntedmc.dataprovider.core.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.core.identity.StackCallerClassLoaderResolver;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
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

    DataProviderHandler(
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
        this.callerContextResolver = Objects.requireNonNull(
                callerContextResolver,
                "Caller context resolver cannot be null."
        );
        ownClassLoader = resourceClassLoader;
        DatabaseConfigMap configMap = new DatabaseConfigMap(dataPath, this.logger, resourceClassLoader);
        executionRuntime = new DataProviderExecutionRuntime(ExecutionRuntimeConfig.from(configHandler.getConfig()));
        DatabaseFactory factory = new DatabaseFactory(
                configMap,
                this.logger,
                executionRuntime,
                callerContextResolver::isKnownPlugin
        );
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
        this.callerContextResolver = Objects.requireNonNull(
                callerContextResolver,
                "Caller context resolver cannot be null."
        );
        this.ownClassLoader = Objects.requireNonNull(ownClassLoader, "Own class loader cannot be null.");
        executionRuntime = null;
    }

    public DatabaseProvider registerDatabaseOrThrow(DatabaseType databaseType, String connectionIdentifier) {
        requireStructuredOpen("registerDatabase");
        PluginId pluginId = resolvePluginId();
        return registerStrict(
                pluginId,
                OwnerScopeId.of(pluginId.value()),
                requireType(databaseType),
                ConnectionIdentifier.of(connectionIdentifier),
                "registerDatabase"
        );
    }

    public DatabaseProvider registerDatabaseForScopeOrThrow(
            OwnerScope ownerScope,
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        requireStructuredOpen("scope.registerDatabase");
        PluginId pluginId = resolvePluginId();
        return registerStrict(
                pluginId,
                OwnerScopeId.from(Objects.requireNonNull(ownerScope, "Owner scope cannot be null.")),
                requireType(databaseType),
                ConnectionIdentifier.of(connectionIdentifier),
                "scope.registerDatabase"
        );
    }

    public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        requireOpen("unregisterDatabase");
        PluginId pluginId = resolvePluginId();
        registry.unregisterDatabase(
                pluginId,
                OwnerScopeId.of(pluginId.value()),
                requireType(databaseType),
                ConnectionIdentifier.of(connectionIdentifier)
        );
    }

    public void unregisterDatabaseForScope(String ownerScope, DatabaseType databaseType, String connectionIdentifier) {
        unregisterDatabaseForScope(OwnerScope.of(ownerScope), databaseType, connectionIdentifier);
    }

    public void unregisterDatabaseForScope(
            OwnerScope ownerScope,
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        requireOpen("scope.unregisterDatabase");
        registry.unregisterDatabase(
                resolvePluginId(),
                OwnerScopeId.from(Objects.requireNonNull(ownerScope, "Owner scope cannot be null.")),
                requireType(databaseType),
                ConnectionIdentifier.of(connectionIdentifier)
        );
    }

    public void unregisterAllDatabases() {
        requireOpen("unregisterAllDatabases");
        PluginId pluginId = resolvePluginId();
        registry.unregisterAllDatabases(pluginId, OwnerScopeId.of(pluginId.value()));
    }

    public void unregisterAllDatabasesForScope(String ownerScope) {
        unregisterAllDatabasesForScope(OwnerScope.of(ownerScope));
    }

    public void unregisterAllDatabasesForScope(OwnerScope ownerScope) {
        requireOpen("scope.unregisterAllDatabases");
        registry.unregisterAllDatabases(
                resolvePluginId(),
                OwnerScopeId.from(Objects.requireNonNull(ownerScope, "Owner scope cannot be null."))
        );
    }

    public void unregisterAllDatabasesForPlugin() {
        requireOpen("unregisterAllDatabasesForPlugin");
        registry.unregisterAllDatabasesForPlugin(resolvePluginId());
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

    /** Resolves the platform-derived identity for API features that need logging context. */
    public String getCurrentPluginId() {
        requireOpen("createOrmContext");
        return resolvePluginId().value();
    }

    /** Verifies that a public handle is used by the plugin that originally obtained it. */
    public void requireCallerIdentity(String expectedPluginId) {
        Objects.requireNonNull(expectedPluginId, "Expected plugin id cannot be null.");
        String actualPluginId = resolvePluginId().value();
        if (!expectedPluginId.equals(actualPluginId)) {
            logger.error("Rejected DataProvider handle use by " + actualPluginId
                    + "; it belongs to " + expectedPluginId + ".");
            throw new SecurityException("This DataProvider handle belongs to a different plugin.");
        }
    }

    /** Binds a facade at the platform API boundary. No stack inspection occurs here. */
    public PluginIdentity issuePluginIdentity(Object platformPlugin) {
        requireOpen("bindPlugin");
        return callerContextResolver.issueIdentity(Objects.requireNonNull(platformPlugin, "Platform plugin cannot be null."));
    }

    /** Verifies a previously issued token without invoking a platform API. */
    public void requireIdentity(PluginIdentity identity) {
        Objects.requireNonNull(identity, "Plugin identity cannot be null.");
        if (!callerContextResolver.isIdentityActive(identity)) {
            logger.error("Rejected DataProvider handle use for an inactive plugin identity.");
            throw new SecurityException("This DataProvider handle belongs to a disabled or replaced plugin.");
        }
    }

    public String getPluginId(PluginIdentity identity) {
        requireOpen("createOrmContext");
        requireIdentity(identity);
        return identity.pluginId();
    }

    public DatabaseProvider registerDatabaseOrThrow(
            PluginIdentity identity, DatabaseType databaseType, String connectionIdentifier
    ) {
        requireStructuredOpen("registerDatabase");
        requireIdentity(identity);
        PluginId pluginId = PluginId.of(identity.pluginId());
        return registerStrict(pluginId, OwnerScopeId.of(pluginId.value()), requireType(databaseType),
                ConnectionIdentifier.of(connectionIdentifier), "registerDatabase");
    }

    public DatabaseProvider registerDatabaseForScopeOrThrow(
            PluginIdentity identity, OwnerScope ownerScope, DatabaseType databaseType, String connectionIdentifier
    ) {
        requireStructuredOpen("scope.registerDatabase");
        requireIdentity(identity);
        return registerStrict(PluginId.of(identity.pluginId()),
                OwnerScopeId.from(Objects.requireNonNull(ownerScope, "Owner scope cannot be null.")),
                requireType(databaseType), ConnectionIdentifier.of(connectionIdentifier), "scope.registerDatabase");
    }

    public void unregisterDatabase(PluginIdentity identity, DatabaseType databaseType, String connectionIdentifier) {
        requireOpen("unregisterDatabase");
        requireIdentity(identity);
        PluginId pluginId = PluginId.of(identity.pluginId());
        registry.unregisterDatabase(pluginId, OwnerScopeId.of(pluginId.value()), requireType(databaseType),
                ConnectionIdentifier.of(connectionIdentifier));
    }

    public void unregisterDatabaseForScope(
            PluginIdentity identity, OwnerScope ownerScope, DatabaseType databaseType, String connectionIdentifier
    ) {
        requireOpen("scope.unregisterDatabase");
        requireIdentity(identity);
        registry.unregisterDatabase(PluginId.of(identity.pluginId()),
                OwnerScopeId.from(Objects.requireNonNull(ownerScope, "Owner scope cannot be null.")),
                requireType(databaseType), ConnectionIdentifier.of(connectionIdentifier));
    }

    public void unregisterAllDatabases(PluginIdentity identity) {
        requireOpen("unregisterAllDatabases");
        requireIdentity(identity);
        PluginId pluginId = PluginId.of(identity.pluginId());
        registry.unregisterAllDatabases(pluginId, OwnerScopeId.of(pluginId.value()));
    }

    public void unregisterAllDatabasesForScope(PluginIdentity identity, OwnerScope ownerScope) {
        requireOpen("scope.unregisterAllDatabases");
        requireIdentity(identity);
        registry.unregisterAllDatabases(PluginId.of(identity.pluginId()),
                OwnerScopeId.from(Objects.requireNonNull(ownerScope, "Owner scope cannot be null.")));
    }

    public void unregisterAllDatabasesForPlugin(PluginIdentity identity) {
        requireOpen("unregisterAllDatabasesForPlugin");
        requireIdentity(identity);
        registry.unregisterAllDatabasesForPlugin(PluginId.of(identity.pluginId()));
    }

    public DatabaseProvider requireRegisteredDatabase(
            PluginIdentity identity, DatabaseType databaseType, String connectionIdentifier
    ) {
        requireStructuredOpen("requireRegisteredDatabase");
        requireIdentity(identity);
        DatabaseType type = requireType(databaseType);
        ConnectionIdentifier identifier = ConnectionIdentifier.of(connectionIdentifier);
        DatabaseProvider provider = registry.getDatabase(PluginId.of(identity.pluginId()), type, identifier);
        if (provider != null) {
            return provider;
        }
        throw missingRegistration(type, identifier.value(), "requireRegisteredDatabase");
    }

    public DatabaseProvider requireRegisteredDatabaseForScope(
            PluginIdentity identity, OwnerScope ownerScope, DatabaseType databaseType, String connectionIdentifier
    ) {
        requireStructuredOpen("scope.requireRegisteredDatabase");
        requireIdentity(identity);
        DatabaseType type = requireType(databaseType);
        ConnectionIdentifier identifier = ConnectionIdentifier.of(connectionIdentifier);
        DatabaseProvider provider = registry.getDatabase(PluginId.of(identity.pluginId()),
                OwnerScopeId.from(Objects.requireNonNull(ownerScope, "Owner scope cannot be null.")), type, identifier);
        if (provider != null) {
            return provider;
        }
        throw missingRegistration(type, identifier.value(), "scope.requireRegisteredDatabase");
    }

    public DatabaseProvider requireRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        requireStructuredOpen("requireRegisteredDatabase");
        DatabaseType type = requireType(databaseType);
        String identifier = ConnectionIdentifier.of(connectionIdentifier).value();
        DatabaseProvider provider = registry.getDatabase(
                resolvePluginId(),
                type,
                ConnectionIdentifier.of(identifier)
        );
        if (provider != null) {
            return provider;
        }
        throw missingRegistration(type, identifier, "requireRegisteredDatabase");
    }

    public DatabaseProvider requireRegisteredDatabaseForScope(
            OwnerScope ownerScope,
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        requireStructuredOpen("scope.requireRegisteredDatabase");
        DatabaseType type = requireType(databaseType);
        ConnectionIdentifier identifier = ConnectionIdentifier.of(connectionIdentifier);
        DatabaseProvider provider = registry.getDatabase(
                resolvePluginId(),
                OwnerScopeId.from(Objects.requireNonNull(ownerScope, "Owner scope cannot be null.")),
                type,
                identifier
        );
        if (provider != null) {
            return provider;
        }
        throw missingRegistration(type, identifier.value(), "scope.requireRegisteredDatabase");
    }

    public ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> getActiveDatabases() {
        requireOpen("getActiveDatabases");
        requireInternalCaller();
        return registry.getActiveDatabases();
    }

    public Map<DatabaseConnectionKey, Integer> getActiveDatabaseReferenceCounts() {
        requireOpen("getActiveDatabaseReferenceCounts");
        requireInternalCaller();
        return registry.getActiveDatabaseReferenceCounts();
    }

    public Map<DatabaseConnectionKey, ConnectionHealthSnapshot> getCachedDatabaseHealth() {
        requireOpen("getCachedDatabaseHealth");
        requireInternalCaller();
        return registry.getCachedHealthSnapshots();
    }

    public CompletableFuture<Void> probeDatabaseHealthAsync() {
        requireOpen("probeDatabaseHealthAsync");
        requireInternalCaller();
        return registry.probeRemoteHealthAsync();
    }

    public Map<DatabaseType, Boolean> getConfiguredDatabaseTypeStates() {
        requireOpen("getConfiguredDatabaseTypeStates");
        requireInternalCaller();
        return registry.getConfiguredDatabaseTypeStates();
    }

    public String getConfiguredOrmSchemaMode() {
        requireOpen("getConfiguredOrmSchemaMode");
        requireInternalCaller();
        return registry.getOrmSchemaMode();
    }

    public void reloadConfiguration() {
        requireOpen("reloadConfiguration");
        requireInternalCaller();
        try {
            registry.reloadConfiguration();
        } catch (RuntimeException failure) {
            throw DataProviderExceptionMapper.configurationFailure(failure, "reloadConfiguration");
        }
    }

    private DatabaseProvider register(
            PluginId pluginId,
            OwnerScopeId ownerScope,
            DatabaseType type,
            ConnectionIdentifier identifier
    ) {
        return DatabaseFactory.withCreationPlugin(
                pluginId,
                () -> registry.registerDatabase(pluginId, ownerScope, type, identifier)
        );
    }

    private DatabaseProvider registerStrict(
            PluginId pluginId,
            OwnerScopeId ownerScope,
            DatabaseType type,
            ConnectionIdentifier identifier,
            String operation
    ) {
        DatabaseProvider provider = register(pluginId, ownerScope, type, identifier);
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

    private static DataProviderRegistrationException missingRegistration(
            DatabaseType type,
            String identifier,
            String operation
    ) {
        return new DataProviderRegistrationException(
                "No active database registration exists for the requested connection.",
                DataProviderFailureContext.of(
                        type,
                        identifier,
                        operation,
                        RetryAdvice.NEVER,
                        ExecutionOutcome.NOT_STARTED
                ),
                null
        );
    }

    private PluginId resolvePluginId() {
        return PluginId.of(resolveCallerContext().pluginId());
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
                INTERNAL_PACKAGE_PREFIX
        );
        if (callerLoader == null || callerLoader != ownClassLoader) {
            logger.error("Rejected privileged operation from non-internal caller.");
            throw new SecurityException("Privileged DataProvider operation is restricted to internal callers.");
        }
    }

    private void requireOpen(String operation) {
        requireStructuredOpen(operation);
    }

    private void requireStructuredOpen(String operation) {
        if (registry.isClosed()) {
            throw new ProviderClosedException(
                    CLOSED_MESSAGE,
                    DataProviderFailureContext.of(
                            null,
                            null,
                            operation,
                            RetryAdvice.NEVER,
                            ExecutionOutcome.NOT_STARTED
                    ),
                    null
            );
        }
    }

    private static DatabaseType requireType(DatabaseType databaseType) {
        return Objects.requireNonNull(databaseType, "Database type cannot be null");
    }
}
