package nl.hauntedmc.dataprovider;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.platform.bukkit.command.DataProviderCommand;
import nl.hauntedmc.dataprovider.platform.bukkit.logger.BukkitLoggerAdapter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public class BukkitDataProvider extends JavaPlugin {

    private static DataProviderApp dataProviderApp;


    @Override
    public void onEnable() {

        BukkitLoggerAdapter logInstance = new BukkitLoggerAdapter(getLogger());
        dataProviderApp = new DataProviderApp(logInstance, getDataPath(), this.getClassLoader());

        // Init Bukkit Command
        DataProviderCommand commandExecutor = new DataProviderCommand(this);
        Objects.requireNonNull(getCommand("dataprovider")).setExecutor(commandExecutor);
        Objects.requireNonNull(getCommand("dataprovider")).setTabCompleter(commandExecutor);

        getLogger().info("Enabled (v" + getDescription().getVersion() + ").");
    }

    @Override
    public void onDisable() {
        dataProviderApp.shutdownAllDatabases();
        getLogger().info("Disabled.");
    }

    public DataProviderApp getDataProvider() {
        return dataProviderApp;
    }

    // START EXTERNALLY ACCESSIBLE
    public static DataProviderAPI getDataProviderAPI() {
        return new DataProviderAPI(dataProviderApp.getDataProviderHandler());
    }
    // END EXTERNALLY ACCESSIBLE
}
