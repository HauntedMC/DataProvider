package nl.hauntedmc.dataprovider.database.config;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Manages individual database config files (e.g. mysql.yml, mongodb.yml, etc.).
 */
public class DatabaseConfigManager {

    private final DataProvider plugin;
    private final File databasesFolder;
    private final Map<DatabaseType, FileConfiguration> configMap = new HashMap<>();

    public DatabaseConfigManager(DataProvider plugin) {
        this.plugin = plugin;
        this.databasesFolder = new File(plugin.getDataFolder(), "databases");
        initializeConfigs();
    }

    private void initializeConfigs() {
        if (!databasesFolder.exists()) {
            databasesFolder.mkdirs();
            plugin.getLogger().info("Created databases folder at: " + databasesFolder.getAbsolutePath());
        }

        for (DatabaseType type : DatabaseType.values()) {
            File configFile = new File(databasesFolder, type.getConfigFileName());
            if (!configFile.exists()) {
                if (!copyDefaultConfigFromResources(type.getConfigFileName(), configFile)) {
                    plugin.getLogger().warning("No default config found for " + type.name()
                            + ". Make sure to create " + configFile.getName() + " manually if needed.");
                }
            }
            if (configFile.exists()) {
                configMap.put(type, YamlConfiguration.loadConfiguration(configFile));
            }
        }
        plugin.getLogger().info("Loaded " + configMap.size() + " database configurations.");
    }

    /**
     * Retrieves the config for a given DatabaseType.
     */
    public FileConfiguration getConfig(DatabaseType type) {
        return configMap.get(type);
    }

    /**
     * Copies a default config from resources to the plugin folder.
     */
    private boolean copyDefaultConfigFromResources(String resourcePath, File destinationFile) {
        try (InputStream in = plugin.getResource("databases/" + resourcePath)) {
            if (in == null) {
                return false;
            }
            Files.copy(in, destinationFile.toPath());
            plugin.getLogger().info("Copied default config: " + resourcePath);
            return true;
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Failed to copy default config: " + resourcePath, e);
            return false;
        }
    }
}
