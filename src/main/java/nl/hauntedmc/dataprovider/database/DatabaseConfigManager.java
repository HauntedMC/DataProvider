package nl.hauntedmc.dataprovider.database;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.logging.DPLogger;
import org.bukkit.configuration.ConfigurationSection;
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
 * Manages individual database configuration files (e.g. mysql.yml, mongodb.yml, etc.).
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
        if (!databasesFolder.exists() && !databasesFolder.mkdirs()) {
            DPLogger.warning("Failed to create databases folder at: " + databasesFolder.getAbsolutePath());
        } else {
            DPLogger.info("Databases folder located at: " + databasesFolder.getAbsolutePath());
        }

        for (DatabaseType type : DatabaseType.values()) {
            File configFile = new File(databasesFolder, type.getConfigFileName());
            if (!configFile.exists()) {
                if (!copyDefaultConfigFromResources(type.getConfigFileName(), configFile)) {
                    DPLogger.warning("No default config found for " + type.name()
                            + ". Please create " + configFile.getName() + " manually if needed.");
                }
            }
            if (configFile.exists()) {
                configMap.put(type, YamlConfiguration.loadConfiguration(configFile));
            }
        }
        DPLogger.info("Loaded " + configMap.size() + " database configurations.");
    }

    public FileConfiguration getConfig(DatabaseType type) {
        return configMap.get(type);
    }

    private boolean copyDefaultConfigFromResources(String resourcePath, File destinationFile) {
        try (InputStream in = plugin.getResource("databases/" + resourcePath)) {
            if (in == null) {
                return false;
            }
            Files.copy(in, destinationFile.toPath());
            DPLogger.info("Copied default config: " + resourcePath);
            return true;
        } catch (IOException e) {
            DPLogger.error("Failed to copy default config: " + resourcePath, e);
            return false;
        }
    }

    public ConfigurationSection getConfig(DatabaseType type, String connectionIdentifier) {
        FileConfiguration config = configMap.get(type);
        if (config != null && config.isConfigurationSection(connectionIdentifier)) {
            return config.getConfigurationSection(connectionIdentifier);
        } else {
            DPLogger.warning("No configuration section found for " + connectionIdentifier + " in " + type.getConfigFileName());
            return null;
        }
    }
}
