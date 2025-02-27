package nl.hauntedmc.dataprovider.config;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ConfigHandler {

    private CommentedConfigurationNode config;
    private final Path configFile;
    private final ConfigurationLoader<CommentedConfigurationNode> loader;

    /**
     * Creates a new ConfigHandler using a default data directory and config file.
     */
    public ConfigHandler() {
        // Define a default data directory, for example "plugins/DataProvider" relative to the working directory.
        Path dataDir = Paths.get("plugins", "DataProvider");
        this.configFile = dataDir.resolve("config.yml");
        this.loader = YamlConfigurationLoader.builder()
                .path(configFile)
                .build();

        ensureConfigFileExists();
        reloadConfig();
        injectMissingKeys();
    }

    /**
     * Ensures the configuration file exists.
     * If not, attempts to copy a default config.yml from the plugin's resources.
     */
    private void ensureConfigFileExists() {
        try {
            Files.createDirectories(configFile.getParent());
            if (!Files.exists(configFile)) {
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile);
                    } else {
                        System.err.println("Default config.yml not found in resources!");
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error ensuring config file exists: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Reloads the configuration from disk.
     */
    public void reloadConfig() {
        try {
            this.config = loader.load();
        } catch (IOException e) {
            System.err.println("Error reloading config file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Injects missing default keys into the config.
     * In this example, we ensure each DatabaseType has a default 'enabled' key.
     */
    private void injectMissingKeys() {
        boolean changed = false;
        for (DatabaseType type : DatabaseType.values()) {
            // The config path is "databases.<type>.enabled"
            CommentedConfigurationNode node = config.node("databases", type.name().toLowerCase(), "enabled");
            if (node.virtual()) {
                try {
                    node.set(true);
                } catch (SerializationException e) {
                    e.printStackTrace();
                }
                changed = true;
            }
        }
        if (changed) {
            saveConfig();
            System.out.println("Updated config.yml with missing default values.");
        }
    }

    /**
     * Saves the current configuration to disk.
     */
    private void saveConfig() {
        try {
            loader.save(config);
        } catch (IOException e) {
            System.err.println("Error saving config file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Checks if a specific DatabaseType is enabled in the configuration.
     *
     * @param type the database type to check.
     * @return true if enabled; otherwise, returns true as a default.
     */
    public boolean isDatabaseTypeEnabled(DatabaseType type) {
        return config.node("databases", type.name().toLowerCase(), "enabled").getBoolean(true);
    }

    /**
     * Returns the root configuration node.
     */
    public CommentedConfigurationNode getConfig() {
        return config;
    }
}
