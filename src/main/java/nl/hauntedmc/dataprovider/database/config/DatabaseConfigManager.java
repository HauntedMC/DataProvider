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

public class DatabaseConfigManager {
    private final DataProvider plugin;
    private final File databasesFolder;
    private final Map<DatabaseType, FileConfiguration> configMap = new HashMap<>();

    public DatabaseConfigManager(DataProvider plugin) {
        this.plugin = plugin;
        this.databasesFolder = new File(plugin.getDataFolder(), "databases");
        initializeConfigs();
    }

    /**
     * Ensures all database configuration files are created at startup.
     * Loads all configurations into memory.
     */
    private void initializeConfigs() {
        if (!databasesFolder.exists()) {
            databasesFolder.mkdirs();
            plugin.getLogger().info("Created databases folder at: " + databasesFolder.getAbsolutePath());
        }

        for (DatabaseType type : DatabaseType.values()) {
            File configFile = new File(databasesFolder, type.getConfigFileName());

            boolean isConfigValid = true;
            if (!configFile.exists()) {
                isConfigValid = copyDefaultConfigFromResources(type.getConfigFileName(), configFile);
            }
            if (isConfigValid) {
                configMap.put(type, YamlConfiguration.loadConfiguration(configFile));
            }
        }
        plugin.getLogger().info("Loaded " + configMap.size() + " database configurations.");
    }

    /**
     * Retrieves the configuration for the specified database type.
     *
     * @param type DatabaseType (MYSQL, MONGODB, etc.)
     * @return FileConfiguration instance
     */
    public FileConfiguration getConfig(DatabaseType type) {
        return configMap.get(type);
    }

    /**
     * Copies the default config from resources to the databases folder.
     */
    private boolean copyDefaultConfigFromResources(String resourcePath, File destinationFile) {
        try (InputStream inputStream = plugin.getResource("databases/" + resourcePath)) {
            if (inputStream == null) {
                plugin.getLogger().warning("Could not find default config in resources: " + resourcePath);
                return false;
            }
            Files.copy(inputStream, destinationFile.toPath());
            plugin.getLogger().info("Copied default config: " + resourcePath);
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to copy default config: " + resourcePath);
            e.printStackTrace();
        }
        return false;
    }
}
