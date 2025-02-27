package nl.hauntedmc.dataprovider;

import nl.hauntedmc.dataprovider.platform.bukkit.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.platform.bukkit.commands.DataProviderCommand;
import nl.hauntedmc.dataprovider.database.internal.DataProviderHandler;
import nl.hauntedmc.dataprovider.config.ConfigHandler;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public class DataProvider extends JavaPlugin {

    private static DataProvider instance;
    private DataProviderHandler dataProviderHandler;
    private ConfigHandler configHandler;


    @Override
    public void onEnable() {
        instance = this;

        // Init Main Config
        configHandler = new ConfigHandler(this);


        // Init Data Provider Handler
        dataProviderHandler = new DataProviderHandler(this);

        // Init Commands
        DataProviderCommand commandExecutor = new DataProviderCommand(this);
        Objects.requireNonNull(getCommand("dataprovider")).setExecutor(commandExecutor);
        Objects.requireNonNull(getCommand("dataprovider")).setTabCompleter(commandExecutor);

        getLogger().info("Enabled (v" + getDescription().getVersion() + ").");
    }

    @Override
    public void onDisable() {
        dataProviderHandler.shutdownAllDatabases();
        getLogger().info("Disabled.");
    }

    public DataProviderHandler getDataProviderHandler(){
        return dataProviderHandler;
    }

    public ConfigHandler getMainConfigHandler() {
        return configHandler;
    }

    // START EXTERNALLY ACCESSIBLE
    public static DataProviderAPI getDataProviderAPI() {
        return new DataProviderAPI(instance.getDataProviderHandler());
    }
    // END EXTERNALLY ACCESSIBLE
}
