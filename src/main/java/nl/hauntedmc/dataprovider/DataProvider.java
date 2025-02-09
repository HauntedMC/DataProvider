package nl.hauntedmc.dataprovider;

import nl.hauntedmc.dataprovider.config.MainConfigManager;
import nl.hauntedmc.dataprovider.command.DataProviderCommand;
import nl.hauntedmc.dataprovider.database.DatabaseConfigManager;
import nl.hauntedmc.dataprovider.logging.DPLogger;
import nl.hauntedmc.dataprovider.registry.DataProviderRegistry;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public class DataProvider extends JavaPlugin {

    private static DataProvider instance;
    private MainConfigManager mainConfigManager;
    private DatabaseConfigManager databaseConfigManager;
    private final DataProviderRegistry registry = new DataProviderRegistry();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        DPLogger.initialize();

        this.mainConfigManager = new MainConfigManager(this);
        this.databaseConfigManager = new DatabaseConfigManager(this);

        DPLogger.info("Enabled (v" + getDescription().getVersion() + ").");

        DataProviderCommand commandExecutor = new DataProviderCommand();
        Objects.requireNonNull(getCommand("dataprovider")).setExecutor(commandExecutor);
        Objects.requireNonNull(getCommand("dataprovider")).setTabCompleter(commandExecutor);
    }

    @Override
    public void onDisable() {
        registry.shutdownAllDatabases();
        DPLogger.info("Disabled.");
    }

    /**
     * Returns the singleton instance of DataProvider.
     */
    public static DataProvider getInstance() {
        return instance;
    }

    /**
     * Returns the MainConfigManager instance.
     */
    public MainConfigManager getMainConfigManager() {
        return mainConfigManager;
    }

    /**
     * Returns the DatabaseConfigManager instance.
     */
    public DatabaseConfigManager getDatabaseConfigManager() {
        return databaseConfigManager;
    }

    /**
     * Returns the DataProviderRegistry instance.
     */
    public DataProviderRegistry getRegistry() {
        return registry;
    }
    
}
