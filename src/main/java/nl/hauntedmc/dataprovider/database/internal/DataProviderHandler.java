package nl.hauntedmc.dataprovider.database.internal;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;
import nl.hauntedmc.dataprovider.logger.DPLogger;
import nl.hauntedmc.dataprovider.security.DataProviderSecurityManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentMap;

/**
 * DataProviderHandler is the single public entry point for database‐related operations.
 * It creates the internal configuration manager and registry and exposes methods
 * (such as registering/unregistering connections) that perform runtime security checks.
 */
public class DataProviderHandler {

    private final DataProviderRegistry registry;

    /**
     * Public constructor.
     *
     * @param plugin the main DataProvider plugin instance.
     */
    public DataProviderHandler(DataProvider plugin) {
        this.registry = new DataProviderRegistry();
        DatabaseConfigMap.initialize();
    }

    /**
     * Authenticates the calling plugin using the secret token.
     *
     * @param plugin the calling JavaPlugin instance (typically “this”)
     * @param token  the secret token provided by the plugin.
     * @return {@code true} if authentication is successful; {@code false} otherwise.
     * @throws SecurityException if the provided plugin instance is not registered.
     */
    public boolean authenticate(JavaPlugin plugin, String token) {
        // Since getProvidingPlugin is nonnull, we can safely retrieve the genuine plugin.
        String pluginName = JavaPlugin.getProvidingPlugin(plugin.getClass()).getName();
        return DataProviderSecurityManager.authorize(pluginName, token);
    }

    /**
     * Registers a database connection for the calling plugin.
     *
     * @param plugin               the calling JavaPlugin instance (typically “this”)
     * @param databaseType         the type of database (e.g. MYSQL, MONGODB, etc.)
     * @param connectionIdentifier a unique identifier for the connection.
     * @return the registered {@link BaseDatabaseProvider} instance.
     * @throws SecurityException if the plugin is not registered or not authorized.
     */
    public BaseDatabaseProvider registerDatabase(JavaPlugin plugin, DatabaseType databaseType, String connectionIdentifier) {
        String pluginName = validateAndGetPluginName(plugin);
        return registry.registerDatabase(pluginName, databaseType, connectionIdentifier);
    }

    /**
     * Unregisters a specific database connection for the calling plugin.
     *
     * @param plugin               the calling JavaPlugin instance.
     * @param databaseType         the type of database.
     * @param connectionIdentifier the connection identifier.
     * @throws SecurityException if the plugin is not registered or not authorized.
     */
    public void unregisterDatabase(JavaPlugin plugin, DatabaseType databaseType, String connectionIdentifier) {
        String pluginName = validateAndGetPluginName(plugin);
        registry.unregisterDatabase(pluginName, databaseType, connectionIdentifier);
    }

    /**
     * Unregisters all database connections for the calling plugin.
     *
     * @param plugin the calling JavaPlugin instance.
     * @throws SecurityException if the plugin is not registered or not authorized.
     */
    public void unregisterAllDatabases(JavaPlugin plugin) {
        String pluginName = validateAndGetPluginName(plugin);
        registry.unregisterAllDatabases(pluginName);
    }

    /**
     * Shuts down all active database connections.
     */
    public void shutdownAllDatabases() {
        checkCallerIsInternal();
        registry.shutdownAllDatabases();
    }

    /**
     * Retrieves a registered database connection for the calling plugin.
     *
     * @param plugin               the calling JavaPlugin instance.
     * @param databaseType         the type of database.
     * @param connectionIdentifier the connection identifier.
     * @return the {@link BaseDatabaseProvider} instance, or {@code null} if not registered.
     * @throws SecurityException if the plugin is not registered or not authorized.
     */
    public BaseDatabaseProvider getRegisteredDatabase(JavaPlugin plugin, DatabaseType databaseType, String connectionIdentifier) {
        String pluginName = validateAndGetPluginName(plugin);
        return registry.getDatabase(pluginName, databaseType, connectionIdentifier);
    }

    /**
     * Returns a view of the active database connections.
     * <p>
     * Note: This method is provided for debugging/administrative purposes.
     * </p>
     *
     * @return a {@link ConcurrentMap} of active connections.
     */
    public ConcurrentMap<DatabaseConnectionKey, BaseDatabaseProvider> getActiveDatabases() {
        checkCallerIsInternal();
        return registry.getActiveDatabases();
    }

    /**
     * Validates that the calling plugin is registered and authorized, then returns its genuine name.
     *
     * @param plugin the calling JavaPlugin instance.
     * @return the genuine plugin name.
     * @throws SecurityException if any validation check fails.
     */
    private @NotNull String validateAndGetPluginName(JavaPlugin plugin) {
        // getProvidingPlugin is assumed to be nonnull.
        JavaPlugin genuine = JavaPlugin.getProvidingPlugin(plugin.getClass());
        String pluginName = genuine.getName();

        if (Bukkit.getPluginManager().getPlugin(pluginName) == null) {
            DPLogger.error("Plugin " + pluginName + " is not registered with Bukkit.");
            throw new SecurityException("Plugin " + pluginName + " is not registered with Bukkit.");
        }
        if (plugin.getClass().getClassLoader() != genuine.getClass().getClassLoader()) {
            DPLogger.error("Class loader mismatch for plugin " + pluginName);
            throw new SecurityException("Class loader mismatch for plugin " + pluginName);
        }
        if (!DataProviderSecurityManager.isAuthorized(pluginName)) {
            DPLogger.error("Plugin " + pluginName + " is not authorized. Please authenticate first.");
            throw new SecurityException("Plugin " + pluginName + " is not authorized. Please authenticate first.");
        }
        return pluginName;
    }

    /**
     * Verifies that the immediate caller belongs to an internal package.
     * <p>
     * This method uses StackWalker (Java 9+) to obtain the caller class. If the caller’s package
     * does not start with "nl.hauntedmc.dataprovider", a SecurityException is thrown.
     * </p>
     *
     * @throws SecurityException if the immediate caller is not internal.
     */
    private void checkCallerIsInternal() {
        // Use StackWalker to retrieve the immediate caller (skipping this method itself).
        Class<?> callerClass = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.skip(1)
                        .findFirst()
                        .map(StackWalker.StackFrame::getDeclaringClass)
                        .orElse(null));
        if (callerClass == null || !callerClass.getPackage().getName().startsWith("nl.hauntedmc.dataprovider")) {
            DPLogger.error("Access denied: " + (callerClass != null ? callerClass.getName() : "unknown caller") +
                    " is not authorized to call this internal method.");
            throw new SecurityException("Access denied: This method is internal only.");
        }
    }
}
