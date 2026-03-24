package nl.hauntedmc.dataprovider.internal;

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
import java.util.regex.Pattern;

/**
 * Public entry point for plugin-scoped database operations.
 * Caller identity is always derived server-side from the active platform.
 */
public class DataProviderHandler {

    private static final String INTERNAL_PACKAGE_PREFIX = "nl.hauntedmc.dataprovider.internal";
    private static final Pattern CONNECTION_IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

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
     */
    public DatabaseProvider registerDatabase(DatabaseType databaseType, String connectionIdentifier) {
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        requireConnectionIdentifier(connectionIdentifier);
        CallerContext caller = resolveCallerContext();
        return registry.registerDatabase(caller.pluginId(), databaseType, connectionIdentifier);
    }

    /**
     * Unregisters a specific database connection for the resolved caller plugin.
     */
    public void unregisterDatabase(DatabaseType databaseType, String connectionIdentifier) {
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        requireConnectionIdentifier(connectionIdentifier);
        CallerContext caller = resolveCallerContext();
        registry.unregisterDatabase(caller.pluginId(), databaseType, connectionIdentifier);
    }

    /**
     * Unregisters all database connections for the resolved caller plugin.
     */
    public void unregisterAllDatabases() {
        CallerContext caller = resolveCallerContext();
        registry.unregisterAllDatabases(caller.pluginId());
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
        Objects.requireNonNull(databaseType, "Database type cannot be null");
        requireConnectionIdentifier(connectionIdentifier);
        CallerContext caller = resolveCallerContext();
        return registry.getDatabase(caller.pluginId(), databaseType, connectionIdentifier);
    }

    /**
     * Returns a snapshot of active database connections.
     */
    public ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> getActiveDatabases() {
        requireInternalCaller();
        return registry.getActiveDatabases();
    }

    /**
     * Returns active connection reference counts per database key.
     */
    public Map<DatabaseConnectionKey, Integer> getActiveDatabaseReferenceCounts() {
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

    private static void requireConnectionIdentifier(String connectionIdentifier) {
        if (connectionIdentifier == null || connectionIdentifier.isBlank()) {
            throw new IllegalArgumentException("Connection identifier cannot be null or blank.");
        }
        if (!CONNECTION_IDENTIFIER_PATTERN.matcher(connectionIdentifier).matches()) {
            throw new IllegalArgumentException("Connection identifier contains unsupported characters.");
        }
    }

    private void requireInternalCaller() {
        ClassLoader callerLoader = StackCallerClassLoaderResolver.resolveNearestCallerOutsidePackage(INTERNAL_PACKAGE_PREFIX);
        if (callerLoader == null || callerLoader != ownClassLoader) {
            logger.error("Rejected privileged operation from non-internal caller.");
            throw new SecurityException("Privileged DataProvider operation is restricted to internal callers.");
        }
    }
}
