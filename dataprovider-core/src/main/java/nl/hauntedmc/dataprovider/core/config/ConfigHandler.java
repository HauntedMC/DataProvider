package nl.hauntedmc.dataprovider.core.config;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.core.security.FilePermissionHardening;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ConfigHandler {

    private static final String DEFAULT_ORM_SCHEMA_MODE = "validate";
    private static final Set<String> SUPPORTED_ORM_SCHEMA_MODES = Set.of("validate", "none", "update", "create");

    private final LoggerAdapter logger;
    private volatile ConfigSnapshot snapshot;
    private final Path configFile;
    private final ConfigurationLoader<CommentedConfigurationNode> loader;

    /**
     * Creates a new ConfigHandler using a default data directory and config file.
     */
    public ConfigHandler(Path dataDir, LoggerAdapter logger) {
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
        Objects.requireNonNull(dataDir, "Data directory cannot be null.");
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
            Path parentDirectory = configFile.getParent();
            Files.createDirectories(parentDirectory);
            FilePermissionHardening.restrictDirectoryToOwner(parentDirectory, logger, "DataProvider config directory");
            if (!Files.exists(configFile)) {
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile);
                    } else {
                        // Keep bootstrap deterministic even if the resource is missing.
                        Files.createFile(configFile);
                        logger.warn("Default config.yml not found in resources. Created an empty config.yml.");
                    }
                }
            }
            FilePermissionHardening.restrictFileToOwner(configFile, logger, "DataProvider config.yml");
        } catch (IOException e) {
            throw new IllegalStateException("Error ensuring config file exists at " + configFile, e);
        }
    }

    /**
     * Reloads the configuration from disk.
     */
    public void reloadConfig() {
        applySnapshot(loadSnapshot());
    }

    /** Loads and validates a complete candidate configuration without changing the active snapshot. */
    public ConfigSnapshot loadSnapshot() {
        try {
            CommentedConfigurationNode candidate = loader.load();
            EnumMap<DatabaseType, Boolean> enabledTypes = new EnumMap<>(DatabaseType.class);
            for (DatabaseType type : DatabaseType.values()) {
                CommentedConfigurationNode enabledNode = candidate.node("databases", type.name().toLowerCase(), "enabled");
                Object rawValue = enabledNode.raw();
                if (rawValue != null && !(rawValue instanceof Boolean)) {
                    throw new IllegalArgumentException("databases." + type.name().toLowerCase() + ".enabled must be boolean.");
                }
                enabledTypes.put(type, rawValue == null || (Boolean) rawValue);
            }
            String schemaMode = candidate.node("orm", "schema_mode").getString(DEFAULT_ORM_SCHEMA_MODE);
            String normalizedSchemaMode = normalizeSchemaMode(schemaMode);
            return new ConfigSnapshot(candidate, enabledTypes, normalizedSchemaMode);
        } catch (IOException e) {
            throw new IllegalStateException("Error loading config file at " + configFile, e);
        }
    }

    /** Atomically replaces the active main configuration with an already validated snapshot. */
    public void applySnapshot(ConfigSnapshot newSnapshot) {
        this.snapshot = Objects.requireNonNull(newSnapshot, "Configuration snapshot cannot be null.");
    }

    /** Returns the active immutable configuration snapshot. */
    public ConfigSnapshot currentSnapshot() {
        ConfigSnapshot activeSnapshot = snapshot;
        if (activeSnapshot == null) {
            throw new IllegalStateException("Configuration is not loaded.");
        }
        return activeSnapshot;
    }

    /**
     * Injects missing default keys into the config.
     * In this example, we ensure each DatabaseType has a default 'enabled' key.
     */
    private void injectMissingKeys() {
        if (snapshot == null) {
            throw new IllegalStateException("Configuration is not loaded.");
        }

        boolean changed = false;
        for (DatabaseType type : DatabaseType.values()) {
            // The config path is "databases.<type>.enabled"
            CommentedConfigurationNode node = snapshot.root().node("databases", type.name().toLowerCase(), "enabled");
            if (node.virtual()) {
                try {
                    node.set(true);
                } catch (SerializationException e) {
                    throw new IllegalStateException("Unable to add missing configuration key for " + type.name(), e);
                }
                changed = true;
            }
        }

        CommentedConfigurationNode ormSchemaModeNode = snapshot.root().node("orm", "schema_mode");
        if (ormSchemaModeNode.virtual()) {
            try {
                ormSchemaModeNode.set(DEFAULT_ORM_SCHEMA_MODE);
            } catch (SerializationException e) {
                throw new IllegalStateException("Unable to add missing ORM schema mode configuration.", e);
            }
            changed = true;
        }

        if (changed) {
            saveConfig();
            reloadConfig();
            logger.info("Updated config.yml with missing default values.");
        }
    }

    /**
     * Saves the current configuration to disk.
     */
    private void saveConfig() {
        try {
            loader.save(snapshot.root());
            FilePermissionHardening.restrictFileToOwner(configFile, logger, "DataProvider config.yml");
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
        if (snapshot == null) {
            throw new IllegalStateException("Configuration is not loaded.");
        }
        return snapshot.enabledTypes().get(type);
    }

    /**
     * Returns a defensive copy of the root configuration node.
     */
    public CommentedConfigurationNode getConfig() {
        if (snapshot == null) {
            throw new IllegalStateException("Configuration is not loaded.");
        }
        return copyNode(snapshot.root());
    }

    /**
     * Returns the validated configured Hibernate schema mode.
     *
     * @return normalized ORM schema mode.
     */
    public String getOrmSchemaMode() {
        if (snapshot == null) {
            throw new IllegalStateException("Configuration is not loaded.");
        }

        return snapshot.ormSchemaMode();
    }

    private static String normalizeSchemaMode(String configuredMode) {
        if (configuredMode == null || configuredMode.isBlank()) {
            return DEFAULT_ORM_SCHEMA_MODE;
        }
        String normalizedMode = configuredMode.trim().toLowerCase(Locale.ROOT);
        if (SUPPORTED_ORM_SCHEMA_MODES.contains(normalizedMode)) {
            return normalizedMode;
        }
        throw new IllegalArgumentException("Invalid orm.schema_mode '" + configuredMode
                + "'. Supported values: update, create, validate, none.");
    }

    public record ConfigSnapshot(
            CommentedConfigurationNode root,
            Map<DatabaseType, Boolean> enabledTypes,
            String ormSchemaMode
    ) {
        public ConfigSnapshot {
            root = copyNode(Objects.requireNonNull(root, "Configuration root cannot be null."));
            enabledTypes = Map.copyOf(enabledTypes);
            Objects.requireNonNull(ormSchemaMode, "ORM schema mode cannot be null.");
        }
    }

    private static CommentedConfigurationNode copyNode(CommentedConfigurationNode node) {
        return (CommentedConfigurationNode) node.copy();
    }
}
