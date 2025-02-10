package nl.hauntedmc.dataprovider.config;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.logger.DPLogger;
import org.bukkit.configuration.file.FileConfiguration;

public class MainConfigManager {
    private final DataProvider plugin;
    private FileConfiguration config;

    public MainConfigManager(DataProvider plugin) {
        this.plugin = plugin;
        loadConfig();
        injectMissingKeys();
    }

    /**
     * Loads the main config.yml
     */
    private void loadConfig() {
        plugin.saveDefaultConfig(); // Ensures config.yml exists
        config = plugin.getConfig();
    }

    /**
     * Injects missing keys into config.yml and saves the file.
     */
    private void injectMissingKeys() {
        boolean changed = false;

        changed |= injectDefault("debug", false);

        for (DatabaseType type : DatabaseType.values()) {
            String path = "databases." + type.name().toLowerCase() + ".enabled";
            changed |= injectDefault(path, true); // Default: true (all databases enabled)
        }

        // Save the file if we added missing values
        if (changed) {
            plugin.saveConfig();
            DPLogger.info("Updated config.yml with missing default values.");
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
        if (!config.contains(path)) {
            config.set(path, value);
            return true;
        }
        return false;
    }

    /**
     * Checks if debug mode is enabled.
     *
     * @return True if debug is enabled, false otherwise.
     */
    public boolean isDebugEnabled() {
        return config.getBoolean("debug", false);
    }

    /**
     * Checks if a specific DatabaseType is enabled in config.yml.
     *
     * @param type The DatabaseType to check
     * @return True if the database type is enabled, false otherwise.
     */
    public boolean isDatabaseTypeEnabled(DatabaseType type) {
        return config.getBoolean("databases." + type.name().toLowerCase() + ".enabled", true);
    }
}
