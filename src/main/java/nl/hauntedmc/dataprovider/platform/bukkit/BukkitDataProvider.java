package nl.hauntedmc.dataprovider.platform.bukkit;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.internal.DataProviderHandler;
import nl.hauntedmc.dataprovider.platform.bukkit.command.DataProviderCommand;
import nl.hauntedmc.dataprovider.platform.bukkit.identity.BukkitCallerContextResolver;
import nl.hauntedmc.dataprovider.platform.bukkit.logger.BukkitLoggerAdapter;
import nl.hauntedmc.dataprovider.platform.common.lifecycle.PlatformDataProviderRuntime;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitDataProvider extends JavaPlugin {

    private static final String COMMAND_NAME = "dataprovider";
    private static final PlatformDataProviderRuntime RUNTIME = new PlatformDataProviderRuntime();


    @Override
    public void onEnable() {
        BukkitLoggerAdapter loggerAdapter = new BukkitLoggerAdapter(getLogger());
        DataProvider provider = RUNTIME.start(
                () -> new DataProvider(
                        loggerAdapter,
                        getDataPath(),
                        getClassLoader(),
                        new BukkitCallerContextResolver(getClassLoader())
                ),
                loggerAdapter
        );
        try {
            registerCommand(provider.getDataProviderHandler());
        } catch (RuntimeException exception) {
            loggerAdapter.error("Failed to initialize Bukkit command wiring.", exception);
            RUNTIME.stop(loggerAdapter);
            throw exception;
        }

        getLogger().info("Enabled (v" + getDescription().getVersion() + ").");
    }

    @Override
    public void onDisable() {
        RUNTIME.stop(new BukkitLoggerAdapter(getLogger()));
        getLogger().info("Disabled.");
    }

    // START EXTERNALLY ACCESSIBLE
    public static DataProviderAPI getDataProviderAPI() {
        return RUNTIME.getDataProviderAPI();
    }
    // END EXTERNALLY ACCESSIBLE

    private void registerCommand(DataProviderHandler handler) {
        PluginCommand command = getCommand(COMMAND_NAME);
        if (command == null) {
            throw new IllegalStateException("Command '" + COMMAND_NAME + "' is missing from plugin.yml.");
        }

        DataProviderCommand commandExecutor = new DataProviderCommand(handler);
        command.setExecutor(commandExecutor);
        command.setTabCompleter(commandExecutor);
    }
}
