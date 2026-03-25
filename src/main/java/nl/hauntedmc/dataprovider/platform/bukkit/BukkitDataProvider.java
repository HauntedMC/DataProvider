package nl.hauntedmc.dataprovider.platform.bukkit;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.platform.bukkit.command.DataProviderCommand;
import nl.hauntedmc.dataprovider.platform.bukkit.identity.BukkitCallerContextResolver;
import nl.hauntedmc.dataprovider.platform.bukkit.logger.BukkitLoggerAdapter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Level;

public class BukkitDataProvider extends JavaPlugin {

    private static volatile DataProvider dataProvider;


    @Override
    public void onEnable() {
        DataProvider previousProvider = dataProvider;
        if (previousProvider != null) {
            getLogger().warning("Detected leftover DataProvider instance during enable; forcing cleanup first.");
            dataProvider = null;
            try {
                previousProvider.shutdownAllDatabases();
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to shut down leftover DataProvider instance.", e);
            }
        }

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
        DataProvider providerToShutdown = dataProvider;
        dataProvider = null;
        if (providerToShutdown != null) {
            try {
                providerToShutdown.shutdownAllDatabases();
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "Failed to shut down DataProvider cleanly.", e);
            }
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
