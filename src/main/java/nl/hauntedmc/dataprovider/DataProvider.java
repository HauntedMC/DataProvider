package nl.hauntedmc.dataprovider;

import org.bukkit.plugin.java.JavaPlugin;

public class DataProvider extends JavaPlugin {

    @Override
    public void onEnable() {
    }

    @Override
    public void onDisable() {
        getLogger().info("DataProvider is shutting down...");
    }
}