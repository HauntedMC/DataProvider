package nl.hauntedmc.dataprovider.platform.bukkit;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
import nl.hauntedmc.dataprovider.platform.bukkit.command.DataProviderCommand;
import nl.hauntedmc.dataprovider.platform.bukkit.identity.BukkitCallerContextResolver;
import nl.hauntedmc.dataprovider.logging.adapters.JulLoggerAdapter;
import nl.hauntedmc.dataprovider.platform.internal.lifecycle.PlatformDataProviderRuntime;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitDataProvider extends JavaPlugin {

    private static final String COMMAND_NAME = "dataprovider";
    private final PlatformDataProviderRuntime runtime = new PlatformDataProviderRuntime();


    @Override
    public void onEnable() {
        JulLoggerAdapter loggerAdapter = new JulLoggerAdapter(getLogger());
        runtime.start(
                () -> new DataProvider(
                        loggerAdapter,
                        getDataPath(),
                        getClassLoader(),
                        new BukkitCallerContextResolver(getClassLoader())
                ),
                this::initializeBindings,
                loggerAdapter
        );

        getLogger().info("Enabled (v" + getDescription().getVersion() + ").");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        runtime.stop(new JulLoggerAdapter(getLogger()));
        getLogger().info("Disabled.");
    }

    private void initializeBindings(DataProvider provider) {
        registerCommand(provider.getDataProviderHandler());
        registerApiService(new DataProviderAPI(provider.getDataProviderHandler()));
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
