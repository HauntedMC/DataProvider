package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.api.OwnerScope;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.config.ConfigHandler;
import nl.hauntedmc.dataprovider.internal.identity.CallerContext;
import nl.hauntedmc.dataprovider.internal.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.internal.identity.StackCallerClassLoaderResolver;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;

import java.util.Objects;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * Public entry point for plugin-scoped database operations.
 * Caller identity is always derived server-side from the active platform.
 */
public class DataProviderHandler {

    private static final String INTERNAL_PACKAGE_PREFIX = "nl.hauntedmc.dataprovider.internal";
    private static final String CLOSED_MESSAGE =
            "DataProvider API is no longer available. Obtain a fresh API instance after plugin enable.";

    private final DataProviderRegistry registry;
    private final CallerContextResolver callerContextResolver;
    private final ILoggerAdapter logger;
    private final ClassLoader ownClassLoader;

    public DataProviderHandler(
            Path dataPath,
            ClassLoader resourceClassLoader,
            ConfigHandler configHandler,
            CallerContextResolver callerContextResolver,
            ILoggerAdapter logger
    ) {
        Objects.requireNonNull(dataPath, "Data path cannot be null.");
        Objects.requireNonNull(resourceClassLoader, "Resource class loader cannot be null.");
        Objects.requireNonNull(configHandler, "Config handler cannot be null.");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
        this.callerContextResolver = Objects.requireNonNull(callerContextResolver, "Caller context resolver cannot be null.");
        this.ownClassLoader = resourceClassLoader;

        DatabaseConfigMap configMap = new DatabaseConfigMap(dataPath, this.logger, resourceClassLoader);
        DatabaseFactory factory = new DatabaseFactory(configMap, this.logger);
        this.registry = new DataProviderRegistry(factory, configHandler, this.logger);
    }

    DataProviderHandler(
            DataProviderRegistry registry,
            CallerContextResolver callerContextResolver,
            ILoggerAdapter logger,
            ClassLoader ownClassLoader
    ) {
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
        this.registry = Objects.requireNonNull(registry, "Registry cannot be null.");
        this.callerContextResolver = Objects.requireNonNull(callerContextResolver, "Caller context resolver cannot be null.");
        this.ownClassLoader = Objects.requireNonNull(ownClassLoader, "Own class loader cannot be null.");
    }

    /**
     * Registers a database connection for the resolved caller plugin.
     * This is the default path for most integrations.
     */
    public DatabaseProvider registerDatabase(DatabaseType databaseType, String connectionIdentifier) {
        requireOpen();
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        CallerContext caller = resolveCallerContext();
        PluginId pluginId = PluginId.of(caller.pluginId());
        ConnectionIdentifier identifier = ConnectionIdentifier.of(connectionIdentifier);
        return registry.registerDatabase(
                pluginId,
                OwnerScopeId.of(pluginId.value()),
                databaseType,
                identifier
        );
    }

    /**
     * Registers a database connection under an explicit owner scope.
     * Used by the optional scoped API facade.
     */
    public DatabaseProvider registerDatabaseForScope(
            String ownerScope,
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        return registerDatabaseForScope(OwnerScope.of(ownerScope), databaseType, connectionIdentifier);
    }

    /**
     * Registers a database connection under a typed explicit owner scope.
     * Used by the optional scoped API facade.
     */
    public DatabaseProvider registerDatabaseForScope(
            OwnerScope ownerScope,
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        requireOpen();
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        CallerContext caller = resolveCallerContext();
        PluginId pluginId = PluginId.of(caller.pluginId());
        ConnectionIdentifier identifier = ConnectionIdentifier.of(connectionIdentifier);
        return registry.registerDatabase(
                pluginId,
                OwnerScopeId.from(ownerScope),
                databaseType,
                identifier
        );
    }

    /**
     * Unregisters a specific database connection for the resolved caller plugin.
     * This is the default path for most integrations.
     */
    public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        requireOpen();
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        CallerContext caller = resolveCallerContext();
        PluginId pluginId = PluginId.of(caller.pluginId());
        ConnectionIdentifier identifier = ConnectionIdentifier.of(connectionIdentifier);
        registry.unregisterDatabase(
                pluginId,
                OwnerScopeId.of(pluginId.value()),
                databaseType,
                identifier
        );
    }

    /**
     * Unregisters a specific database connection under an explicit owner scope.
     * Used by the optional scoped API facade.
     */
    public void unregisterDatabaseForScope(
            String ownerScope,
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        unregisterDatabaseForScope(OwnerScope.of(ownerScope), databaseType, connectionIdentifier);
    }

    /**
     * Unregisters a specific database connection under a typed explicit owner scope.
     * Used by the optional scoped API facade.
     */
    public void unregisterDatabaseForScope(
            OwnerScope ownerScope,
            DatabaseType databaseType,
            String connectionIdentifier
    ) {
        requireOpen();
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        CallerContext caller = resolveCallerContext();
        PluginId pluginId = PluginId.of(caller.pluginId());
        ConnectionIdentifier identifier = ConnectionIdentifier.of(connectionIdentifier);
        registry.unregisterDatabase(
                pluginId,
                OwnerScopeId.from(ownerScope),
                databaseType,
                identifier
        );
    }

    /**
     * Unregisters all database connections for the resolved caller plugin default owner scope.
     */
    public void unregisterAllDatabases() {
        requireOpen();
        CallerContext caller = resolveCallerContext();
        PluginId pluginId = PluginId.of(caller.pluginId());
        registry.unregisterAllDatabases(pluginId, OwnerScopeId.of(pluginId.value()));
    }

    /**
     * Unregisters all database connections under an explicit owner scope.
     * Used by the optional scoped API facade.
     */
    public void unregisterAllDatabasesForScope(String ownerScope) {
        unregisterAllDatabasesForScope(OwnerScope.of(ownerScope));
    }

    /**
     * Unregisters all database connections under a typed explicit owner scope.
     * Used by the optional scoped API facade.
     */
    public void unregisterAllDatabasesForScope(OwnerScope ownerScope) {
        requireOpen();
        Objects.requireNonNull(ownerScope, "Owner scope cannot be null.");
        CallerContext caller = resolveCallerContext();
        PluginId pluginId = PluginId.of(caller.pluginId());
        registry.unregisterAllDatabases(pluginId, OwnerScopeId.from(ownerScope));
    }

    /**
     * Unregisters all database connections for the resolved caller plugin across all caller scopes.
     * Intended for full plugin shutdown where registrations may originate from multiple owner scopes.
     */
    public void unregisterAllDatabasesForPlugin() {
        requireOpen();
        CallerContext caller = resolveCallerContext();
        registry.unregisterAllDatabasesForPlugin(PluginId.of(caller.pluginId()));
    }

    /**
     * Shuts down all active database connections.
     */
    public void shutdownAllDatabases() {
        requireInternalCaller();
        registry.shutdownAllDatabases();
    }

    /**
     * Retrieves a registered database connection for the resolved caller plugin.
     */
    public DatabaseProvider getRegisteredDatabase(DatabaseType databaseType, String connectionIdentifier) {
        requireOpen();
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        CallerContext caller = resolveCallerContext();
        return registry.getDatabase(
                PluginId.of(caller.pluginId()),
                databaseType,
                ConnectionIdentifier.of(connectionIdentifier)
        );
    }

    /**
     * Returns a snapshot of active database connections.
     */
    public ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> getActiveDatabases() {
        requireOpen();
        requireInternalCaller();
        return registry.getActiveDatabases();
    }

    /**
     * Returns active connection reference counts per database key.
     */
    public Map<DatabaseConnectionKey, Integer> getActiveDatabaseReferenceCounts() {
        requireOpen();
        requireInternalCaller();
        return registry.getActiveDatabaseReferenceCounts();
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
        ClassLoader callerLoader = StackCallerClassLoaderResolver.resolveNearestCallerOutsidePackage(INTERNAL_PACKAGE_PREFIX);
        if (callerLoader == null || callerLoader != ownClassLoader) {
            logger.error("Rejected privileged operation from non-internal caller.");
            throw new SecurityException("Privileged DataProvider operation is restricted to internal callers.");
        }
    }

    private void requireOpen() {
        if (registry.isClosed()) {
            throw new IllegalStateException(CLOSED_MESSAGE);
        }
    }
}
