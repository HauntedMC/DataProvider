package nl.hauntedmc.dataprovider.platform.bukkit;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.core.DataProvider;
import nl.hauntedmc.dataprovider.core.DataProviderHandler;
import nl.hauntedmc.dataprovider.core.api.DefaultDataProviderApi;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.platform.bukkit.command.DataProviderCommand;
import nl.hauntedmc.dataprovider.platform.bukkit.identity.BukkitCallerContextResolver;
import nl.hauntedmc.dataprovider.platform.common.lifecycle.PlatformDataProviderRuntime;
import nl.hauntedmc.dataprovider.platform.common.logging.JulLoggerAdapter;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class BukkitDataProvider extends JavaPlugin {

    private static final String COMMAND_NAME = "dataprovider";
    private final PlatformDataProviderRuntime runtime = new PlatformDataProviderRuntime();
    private BukkitCallerContextResolver identityResolver;
    private DataProviderHandler activeHandler;

    @Override
    public void onEnable() {
        JulLoggerAdapter loggerAdapter = new JulLoggerAdapter(getLogger());
        identityResolver = new BukkitCallerContextResolver(getClassLoader());
        identityResolver.synchronizePlugins();
        getServer().getPluginManager().registerEvents(new IdentityLifecycleListener(), this);
        runtime.start(
                () -> new DataProvider(
                        loggerAdapter,
                        getDataPath(),
                        getClassLoader(),
                        identityResolver
                ),
                this::initializeBindings,
                loggerAdapter
        );

        getLogger().info("DataProvider enabled (v" + getDescription().getVersion() + ").");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        try {
            runtime.stop(new JulLoggerAdapter(getLogger()));
        } finally {
            if (identityResolver != null) {
                identityResolver.invalidateAll();
            }
            activeHandler = null;
        }
        getLogger().info("DataProvider disabled.");
    }

    private final class IdentityLifecycleListener implements Listener {
        @EventHandler
        public void onPluginEnable(PluginEnableEvent event) {
            identityResolver.register(event.getPlugin());
        }

        @EventHandler
        public void onPluginDisable(PluginDisableEvent event) {
            if (event.getPlugin() == BukkitDataProvider.this) {
                return;
            }
            PluginIdentity identity = identityResolver.beginDisable(event.getPlugin());
            if (identity == null) {
                return;
            }
            if (activeHandler != null) {
                try {
                    activeHandler.unregisterAllDatabasesForPlugin(identity);
                } catch (RuntimeException | Error failure) {
                    getLogger().log(
                            Level.SEVERE,
                            "Failed to force cleanup DataProvider resources for disabling plugin '"
                                    + event.getPlugin().getName() + "'. Remaining resources will be closed during "
                                    + "DataProvider shutdown.",
                            failure
                    );
                }
            }
            try {
                getServer().getScheduler().runTask(BukkitDataProvider.this, () ->
                        identityResolver.invalidate(event.getPlugin(), identity));
            } catch (RuntimeException failure) {
                getLogger().log(
                        Level.WARNING,
                        "Could not schedule final identity invalidation for plugin '"
                                + event.getPlugin().getName() + "'. Global DataProvider shutdown will finalize it.",
                        failure
                );
            }
        }
    }

    private void initializeBindings(DataProvider provider) {
        activeHandler = provider.getDataProviderHandler();
        registerCommand(activeHandler);
        registerApiService(new DefaultDataProviderApi(activeHandler));
    }

    private void registerCommand(DataProviderHandler handler) {
        PluginCommand command = getCommand(COMMAND_NAME);
        if (command == null) {
            throw new IllegalStateException("Command '" + COMMAND_NAME + "' is missing from plugin.yml.");
        }

        DataProviderCommand commandExecutor = new DataProviderCommand(handler);
        command.setExecutor(commandExecutor);
        command.setTabCompleter(commandExecutor);
    }

    private void registerApiService(DataProviderAPI dataProviderAPI) {
        getServer().getServicesManager().register(
                DataProviderAPI.class,
                dataProviderAPI,
                this,
                ServicePriority.Normal
        );
    }
}
