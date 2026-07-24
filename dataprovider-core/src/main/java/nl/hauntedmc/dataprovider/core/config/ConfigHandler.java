package nl.hauntedmc.dataprovider.core.config;

import nl.hauntedmc.dataprovider.core.concurrent.ExecutionRuntimeConfig;
import nl.hauntedmc.dataprovider.core.resilience.ResilienceRuntimeConfig;
import nl.hauntedmc.dataprovider.core.security.FilePermissionHardening;
import nl.hauntedmc.dataprovider.database.DatabaseType;
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
            // Execution lanes are runtime-scoped, but every reload must still reject invalid future settings.
            ExecutionRuntimeConfig.from(candidate);
            ResilienceRuntimeConfig.from(candidate);
            return new ConfigSnapshot(candidate, enabledTypes, normalizedSchemaMode);
        } catch (IOException e) {
            throw new IllegalStateException("Error loading config file at " + configFile, e);
        }
    }

    public void applySnapshot(ConfigSnapshot newSnapshot) {
        this.snapshot = Objects.requireNonNull(newSnapshot, "Configuration snapshot cannot be null.");
    }

    public ConfigSnapshot currentSnapshot() {
        ConfigSnapshot activeSnapshot = snapshot;
        if (activeSnapshot == null) {
            throw new IllegalStateException("Configuration is not loaded.");
        }
        return activeSnapshot;
    }

    private void injectMissingKeys() {
        if (snapshot == null) {
            throw new IllegalStateException("Configuration is not loaded.");
        }

        boolean changed = false;
        for (DatabaseType type : DatabaseType.values()) {
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

        changed |= injectDefault("resilience", "workers", 2);
        changed |= injectDefault("resilience", "queue_capacity", 128);
        changed |= injectDefault("resilience", "health_interval_ms", 15_000);
        changed |= injectDefault("resilience", "stale_threshold_ms", 45_000);
        changed |= injectDefault("resilience", "failure_threshold", 3);
        changed |= injectDefault("resilience", "recovery_threshold", 1);
        changed |= injectDefault("resilience", "initial_backoff_ms", 1_000);
        changed |= injectDefault("resilience", "max_backoff_ms", 30_000);
        changed |= injectDefault("resilience", "jitter", 0.20D);
        changed |= injectDefault("resilience", "shutdown_grace_ms", 2_000);

        if (changed) {
            saveConfig();
            reloadConfig();
            logger.info("Updated config.yml with missing default values.");
        }
    }

    private boolean injectDefault(Object... pathAndValue) {
        Object value = pathAndValue[pathAndValue.length - 1];
        Object[] path = java.util.Arrays.copyOf(pathAndValue, pathAndValue.length - 1);
        CommentedConfigurationNode node = snapshot.root().node(path);
        if (!node.virtual()) {
            return false;
        }
        try {
            node.set(value);
            return true;
        } catch (SerializationException exception) {
            throw new IllegalStateException("Unable to add missing resilience configuration key.", exception);
        }
    }

    private void saveConfig() {
        try {
            loader.save(snapshot.root());
            FilePermissionHardening.restrictFileToOwner(configFile, logger, "DataProvider config.yml");
        } catch (IOException e) {
            throw new IllegalStateException("Error saving config file at " + configFile, e);
        }
    }

    public boolean isDatabaseTypeEnabled(DatabaseType type) {
        if (snapshot == null) {
            throw new IllegalStateException("Configuration is not loaded.");
        }
        return snapshot.enabledTypes().get(type);
    }

    public CommentedConfigurationNode getConfig() {
        if (snapshot == null) {
            throw new IllegalStateException("Configuration is not loaded.");
        }
        return copyNode(snapshot.root());
    }

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
