package nl.hauntedmc.dataprovider.util;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.logger.DPLogger;

public class ConfigUtils {

    public static void initializeMainConfig(){
        DataProvider.getInstance().saveDefaultConfig();
        injectMissingKeys();
    }

    /**
     * Injects missing keys into config.yml and saves the file.
     */
    private static void injectMissingKeys() {
        boolean changed = false;

        for (DatabaseType type : DatabaseType.values()) {
            String path = "databases." + type.name().toLowerCase() + ".enabled";
            changed |= injectDefault(path, true);
        }

        // Save the file if we added missing values
        if (changed) {
            DataProvider.getInstance().saveConfig();
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
    private static boolean injectDefault(String path, Object value) {
        if (!DataProvider.getInstance().getConfig().contains(path)) {
            DataProvider.getInstance().getConfig().set(path, value);
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
    public static boolean isDatabaseTypeEnabled(DatabaseType type) {
        return DataProvider.getInstance().getConfig().getBoolean("databases." + type.name().toLowerCase() + ".enabled", true);
    }
}
