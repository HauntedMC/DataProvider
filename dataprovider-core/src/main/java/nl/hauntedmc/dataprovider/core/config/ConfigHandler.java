package nl.hauntedmc.dataprovider.core.config;

import nl.hauntedmc.dataprovider.core.concurrent.ExecutionRuntimeConfig;
import nl.hauntedmc.dataprovider.core.resilience.ResilienceRuntimeConfig;
import nl.hauntedmc.dataprovider.core.security.FilePermissionHardening;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
        reconcileWithBundledDefaults();
        snapshot = readSnapshot();
    }

    private void ensureConfigFileExists() {
        try {
            Path parentDirectory = configFile.getParent();
            Files.createDirectories(parentDirectory);
            FilePermissionHardening.restrictDirectoryToOwner(parentDirectory, logger, "DataProvider config directory");
            if (!Files.exists(configFile)) {
                try (InputStream in = ConfigHandler.class.getResourceAsStream("/config.yml")) {
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
        return readSnapshot();
    }

    private ConfigSnapshot readSnapshot() {
        try {
            CommentedConfigurationNode candidate = loader.load();
            EnumMap<DatabaseType, Boolean> enabledTypes = new EnumMap<>(DatabaseType.class);
            for (DatabaseType type : DatabaseType.values()) {
                String configKey = type.configKey();
                CommentedConfigurationNode enabledNode = candidate.node("databases", configKey, "enabled");
                Object rawValue = enabledNode.raw();
                if (rawValue != null && !(rawValue instanceof Boolean)) {
                    throw new IllegalArgumentException("databases." + configKey + ".enabled must be boolean.");
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

    private void reconcileWithBundledDefaults() {
        try {
            CommentedConfigurationNode configured = loader.load();
            CommentedConfigurationNode defaults = loadBundledDefaults();
            if (ConfigurationReconciler.reconcileSchema(configured, defaults)) {
                AtomicConfigurationWriter.save(configFile, configured, logger, "DataProvider config.yml");
                logger.info("Reconciled config.yml with the current default schema.");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Error reconciling config file at " + configFile, e);
        }
    }

    private CommentedConfigurationNode loadBundledDefaults() throws IOException {
        if (ConfigHandler.class.getResource("/config.yml") == null) {
            throw new IOException("Bundled config.yml resource is missing.");
        }
        return YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new InputStreamReader(
                        Objects.requireNonNull(ConfigHandler.class.getResourceAsStream("/config.yml")), StandardCharsets.UTF_8
                )))
                .build()
                .load();
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
        return node.copy();
    }
}
