package nl.hauntedmc.dataprovider;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.commands.DataProviderCommand;
import nl.hauntedmc.dataprovider.database.internal.DataProviderHandler;
import nl.hauntedmc.dataprovider.logger.DPLogger;
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

        // Init Logger
        DPLogger.initialize(this);

        // Init Data Provider Handler
        dataProviderHandler = new DataProviderHandler(this);

        // Init Commands
        DataProviderCommand commandExecutor = new DataProviderCommand(this);
        Objects.requireNonNull(getCommand("dataprovider")).setExecutor(commandExecutor);
        Objects.requireNonNull(getCommand("dataprovider")).setTabCompleter(commandExecutor);

        DPLogger.info("Enabled (v" + getDescription().getVersion() + ").");
    }

    @Override
    public void onDisable() {
        dataProviderHandler.shutdownAllDatabases();
        DPLogger.info("Disabled.");
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
