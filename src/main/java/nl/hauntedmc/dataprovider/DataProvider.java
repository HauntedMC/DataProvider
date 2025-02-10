package nl.hauntedmc.dataprovider;

import nl.hauntedmc.dataprovider.commands.DataProviderCommand;
import nl.hauntedmc.dataprovider.database.internal.DataProviderHandler;
import nl.hauntedmc.dataprovider.logger.DPLogger;
import nl.hauntedmc.dataprovider.security.DataProviderSecurityManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public class DataProvider extends JavaPlugin {

    private static DataProvider instance;
    private DataProviderHandler dataProviderHandler;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Init Logger
        DPLogger.initialize();

        // Init Security Manager
        DataProviderSecurityManager.initialize();

        // Init Data Provider Handler
        dataProviderHandler = new DataProviderHandler(this);

        // Init Commands
        DataProviderCommand commandExecutor = new DataProviderCommand();
        Objects.requireNonNull(getCommand("dataprovider")).setExecutor(commandExecutor);
        Objects.requireNonNull(getCommand("dataprovider")).setTabCompleter(commandExecutor);

        DPLogger.info("Enabled (v" + getDescription().getVersion() + ").");
    }

    @Override
    public void onDisable() {
        dataProviderHandler.shutdownAllDatabases();
        DPLogger.info("Disabled.");
    }

    /**
     * Returns the singleton instance of DataProvider.
     */
    public static DataProvider getInstance() {
        return instance;
    }


    /**
     * Returns the DataProviderRegistry instance.
     */
    public DataProviderHandler getDataProviderHandler() {
        return dataProviderHandler;
    }

}
