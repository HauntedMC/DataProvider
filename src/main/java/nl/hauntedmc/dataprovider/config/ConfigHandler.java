package nl.hauntedmc.dataprovider.config;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

public class ConfigHandler {

    private static final String DEFAULT_ORM_SCHEMA_MODE = "update";
    private static final Set<String> SUPPORTED_ORM_SCHEMA_MODES = Set.of("update", "create", "validate", "none");

    private CommentedConfigurationNode config;
    private final Path configFile;
    private final ConfigurationLoader<CommentedConfigurationNode> loader;

    /**
     * Creates a new ConfigHandler using a default data directory and config file.
     */
    public ConfigHandler() {
        // Define a default data directory, for example "plugins/DataProvider" relative to the working directory.
        Path dataDir = DataProvider.getDataPath();
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
                        // Keep bootstrap deterministic even if the resource is missing.
                        Files.createFile(configFile);
                        DataProvider.getLogger().warn("Default config.yml not found in resources. Created an empty config.yml.");
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Error ensuring config file exists at " + configFile, e);
        }
    }

    /**
     * Reloads the configuration from disk.
     */
    public void reloadConfig() {
        try {
            this.config = loader.load();
        } catch (IOException e) {
            throw new IllegalStateException("Error reloading config file at " + configFile, e);
        }
    }

    /**
     * Injects missing default keys into the config.
     * In this example, we ensure each DatabaseType has a default 'enabled' key.
     */
    private void injectMissingKeys() {
        if (config == null) {
            throw new IllegalStateException("Configuration is not loaded.");
        }

        boolean changed = false;
        for (DatabaseType type : DatabaseType.values()) {
            // The config path is "databases.<type>.enabled"
            CommentedConfigurationNode node = config.node("databases", type.name().toLowerCase(), "enabled");
            if (node.virtual()) {
                try {
                    node.set(true);
                } catch (SerializationException ignored) {
                }
                changed = true;
            }
        }

        CommentedConfigurationNode ormSchemaModeNode = config.node("orm", "schema_mode");
        if (ormSchemaModeNode.virtual()) {
            try {
                ormSchemaModeNode.set(DEFAULT_ORM_SCHEMA_MODE);
            } catch (SerializationException ignored) {
            }
            changed = true;
        }

        if (changed) {
            saveConfig();
            DataProvider.getLogger().info("Updated config.yml with missing default values.");
        }
    }

    /**
     * Saves the current configuration to disk.
     */
    private void saveConfig() {
        try {
            loader.save(config);
        } catch (IOException e) {
            throw new IllegalStateException("Error saving config file at " + configFile, e);
        }
    }

    /**
     * Checks if a specific DatabaseType is enabled in the configuration.
     *
     * @param type the database type to check.
     * @return true if enabled; otherwise, returns true as a default.
     */
    public boolean isDatabaseTypeEnabled(DatabaseType type) {
        if (config == null) {
            throw new IllegalStateException("Configuration is not loaded.");
        }
        return config.node("databases", type.name().toLowerCase(), "enabled").getBoolean(true);
    }

    /**
     * Returns the root configuration node.
     */
    public CommentedConfigurationNode getConfig() {
        if (config == null) {
            throw new IllegalStateException("Configuration is not loaded.");
        }
        return config;
    }

    /**
     * Returns the configured Hibernate schema mode with strict fallback.
     *
     * @return normalized ORM schema mode.
     */
    public String getOrmSchemaMode() {
        if (config == null) {
            throw new IllegalStateException("Configuration is not loaded.");
        }

        String configuredMode = config.node("orm", "schema_mode").getString(DEFAULT_ORM_SCHEMA_MODE);
        if (configuredMode == null || configuredMode.isBlank()) {
            return DEFAULT_ORM_SCHEMA_MODE;
        }

        String normalizedMode = configuredMode.trim().toLowerCase(Locale.ROOT);
        if (SUPPORTED_ORM_SCHEMA_MODES.contains(normalizedMode)) {
            return normalizedMode;
        }

        DataProvider.getLogger().warn("Invalid orm.schema_mode '" + configuredMode
                + "'. Falling back to '" + DEFAULT_ORM_SCHEMA_MODE
                + "'. Supported values: update, create, validate, none.");
        return DEFAULT_ORM_SCHEMA_MODE;
    }
}
