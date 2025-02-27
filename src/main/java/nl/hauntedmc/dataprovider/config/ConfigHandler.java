package nl.hauntedmc.dataprovider.config;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import org.bukkit.plugin.Plugin;

public class ConfigHandler {

    private final Plugin plugin;

    public ConfigHandler(DataProvider plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        injectMissingKeys();
    }

    /**
     * Injects missing keys into config.yml and saves the file.
     */
    private void injectMissingKeys() {
        boolean changed = false;

        for (DatabaseType type : DatabaseType.values()) {
            String path = "databases." + type.name().toLowerCase() + ".enabled";
            changed |= injectDefault(path, true);
        }

        // Save the file if we added missing values
        if (changed) {
            plugin.saveConfig();
            plugin.getLogger().info("Updated config.yml with missing default values.");
        }
    }

    /**
     * Injects a default value into config.yml if the key is missing.
     *
     * @param path  The path in the config (e.g., "debug", "databases.mysql.enabled")
     * @param value The default value to inject
     * @return true if a missing key was injected, false otherwise
     */
    private boolean injectDefault(String path, Object value) {
        if (!plugin.getConfig().contains(path)) {
            plugin.getConfig().set(path, value);
            return true;
        }
        return false;
    }

    /**
     * Checks if a specific DatabaseType is enabled in config.yml.
     *
     * @param type The DatabaseType to check
     * @return True if the database type is enabled, false otherwise.
     */
    public boolean isDatabaseTypeEnabled(DatabaseType type) {
        return plugin.getConfig().getBoolean("databases." + type.name().toLowerCase() + ".enabled", true);
    }
}
