package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.DataProviderApp;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

class DatabaseConfigMap {

    private static final Map<DatabaseType, CommentedConfigurationNode> configMap = new HashMap<>();

    protected DatabaseConfigMap() {
        initialize();
    }

    private void initialize() {
        File databasesFolder = new File(String.valueOf(DataProviderApp.getDataPath()), "databases");
        if (!databasesFolder.exists() && !databasesFolder.mkdirs()) {
            DataProviderApp.getLogger().warn("Failed to create databases folder at: " + databasesFolder.getAbsolutePath());
        } else {
            DataProviderApp.getLogger().info("Databases folder located at: " + databasesFolder.getAbsolutePath());
        }

        for (DatabaseType type : DatabaseType.values()) {
            File configFile = new File(databasesFolder, type.getConfigFileName());
            if (!configFile.exists()) {
                if (!copyDefaultConfigFromResources(type.getConfigFileName(), configFile)) {
                    DataProviderApp.getLogger().warn("No default config found for " + type.name()
                            + ". Please create " + configFile.getName() + " manually if needed.");
                }
            }
            if (configFile.exists()) {
                Path path = configFile.toPath();
                ConfigurationLoader<CommentedConfigurationNode> loader = YamlConfigurationLoader.builder()
                        .path(path)
                        .build();
                try {
                    CommentedConfigurationNode node = loader.load();
                    configMap.put(type, node);
                } catch (IOException e) {
                    DataProviderApp.getLogger().error("Failed to load config for " + type.name(), e);
                }
            }
        }
        DataProviderApp.getLogger().info("Loaded " + configMap.size() + " database configurations.");
    }

    private boolean copyDefaultConfigFromResources(String resourcePath, File destinationFile) {
        try (InputStream in = DataProviderApp.getResource("databases/" + resourcePath)) {
            if (in == null) {
                return false;
            }
            Files.copy(in, destinationFile.toPath());
            DataProviderApp.getLogger().info("Copied default config: " + resourcePath);
            return true;
        } catch (IOException e) {
            DataProviderApp.getLogger().error("Failed to copy default config: " + resourcePath, e);
            return false;
        }
    }

    /**
     * Returns the configuration section for the given connection identifier from the database configuration.
     *
     * @param type                 the DatabaseType
     * @param connectionIdentifier the identifier for a specific connection section
     * @return the corresponding CommentedConfigurationNode, or null if not found.
     */
    protected CommentedConfigurationNode getConfig(DatabaseType type, String connectionIdentifier) {
        CommentedConfigurationNode config = configMap.get(type);
        if (config != null) {
            CommentedConfigurationNode section = config.node(connectionIdentifier);
            if (section.virtual()) {
                DataProviderApp.getLogger().warn("No configuration section found for " + connectionIdentifier + " in " + type.getConfigFileName());
                return null;
            }
            return section;
        } else {
            DataProviderApp.getLogger().warn("No configuration loaded for database type " + type.name());
            return null;
        }
    }
}
