package nl.hauntedmc.dataprovider.platform.bukkit;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.platform.bukkit.command.DataProviderCommand;
import nl.hauntedmc.dataprovider.platform.bukkit.identity.BukkitCallerContextResolver;
import nl.hauntedmc.dataprovider.platform.bukkit.logger.BukkitLoggerAdapter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public class BukkitDataProvider extends JavaPlugin {

    private static DataProvider dataProvider;


    @Override
    public void onEnable() {

        BukkitLoggerAdapter logInstance = new BukkitLoggerAdapter(getLogger());
        dataProvider = new DataProvider(
                logInstance,
                getDataPath(),
                this.getClassLoader(),
                new BukkitCallerContextResolver(this.getClassLoader())
        );

        // Init Bukkit Command
        DataProviderCommand commandExecutor = new DataProviderCommand(dataProvider.getDataProviderHandler());
        Objects.requireNonNull(getCommand("dataprovider")).setExecutor(commandExecutor);
        Objects.requireNonNull(getCommand("dataprovider")).setTabCompleter(commandExecutor);

        getLogger().info("Enabled (v" + getDescription().getVersion() + ").");
    }

    @Override
    public void onDisable() {
        if (dataProvider != null) {
            dataProvider.shutdownAllDatabases();
        }
        getLogger().info("Disabled.");
    }

    // START EXTERNALLY ACCESSIBLE
    public static DataProviderAPI getDataProviderAPI() {
        if (dataProvider == null) {
            throw new IllegalStateException("DataProvider is not initialized yet.");
        }
        return new DataProviderAPI(dataProvider.getDataProviderHandler());
    }
    // END EXTERNALLY ACCESSIBLE
}
