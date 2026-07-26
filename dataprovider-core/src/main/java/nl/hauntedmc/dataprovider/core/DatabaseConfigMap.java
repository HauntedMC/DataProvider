package nl.hauntedmc.dataprovider.core;

import nl.hauntedmc.dataprovider.core.config.AtomicConfigurationWriter;
import nl.hauntedmc.dataprovider.core.security.FilePermissionHardening;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

class DatabaseConfigMap {

    private final Path dataPath;
    private final LoggerAdapter logger;
    private final ClassLoader resourceClassLoader;
    private volatile Map<DatabaseType, CommentedConfigurationNode> configMap = Map.of();

    protected DatabaseConfigMap(Path dataPath, LoggerAdapter logger, ClassLoader resourceClassLoader) {
        this.dataPath = Objects.requireNonNull(dataPath, "Data path cannot be null.");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
        this.resourceClassLoader = Objects.requireNonNull(resourceClassLoader, "Resource class loader cannot be null.");
        initialize();
    }

    private void initialize() {
        Map<DatabaseType, CommentedConfigurationNode> loadedConfigs = new EnumMap<>(DatabaseType.class);
        Path databasesPath = dataPath.resolve("databases");
        File databasesFolder = databasesPath.toFile();
        if (!databasesFolder.exists() && !databasesFolder.mkdirs()) {
            logger.warn("Failed to create databases folder at: " + databasesFolder.getAbsolutePath());
        } else {
            logger.info("Databases folder located at: " + databasesFolder.getAbsolutePath());
        }
        FilePermissionHardening.restrictDirectoryToOwner(databasesPath, logger, "database configuration directory");

        for (DatabaseType type : DatabaseType.values()) {
            File configFile = new File(databasesFolder, type.getConfigFileName());
            if (!configFile.exists()) {
                if (!copyDefaultConfigFromResources(type.getConfigFileName(), configFile)) {
                    logger.warn("No default config found for " + type.name()
                            + ". Please create " + configFile.getName() + " manually if needed.");
                }
            }
            if (configFile.exists()) {
                Path path = configFile.toPath();
                FilePermissionHardening.restrictFileToOwner(path, logger, type.name() + " database config");
                ConfigurationLoader<CommentedConfigurationNode> loader = YamlConfigurationLoader.builder()
                        .path(path)
                        .build();
                try {
                    CommentedConfigurationNode node = loader.load();
                    if (refreshBundledDefault(type, path, node)) {
                        node = loader.load();
                    }
                    loadedConfigs.put(type, node);
                } catch (IOException e) {
                    logger.error("Failed to load config for " + type.name(), e);
                }
            }
        }
        configMap = Map.copyOf(loadedConfigs);
        logger.info("Loaded " + configMap.size() + " database configurations.");
    }

    /** Loads every database configuration as a candidate snapshot without changing active configuration. */
    protected DatabaseConfigSnapshot loadSnapshot() {
        Path databasesPath = dataPath.resolve("databases");
        EnumMap<DatabaseType, CommentedConfigurationNode> loadedConfigs = new EnumMap<>(DatabaseType.class);
        for (DatabaseType type : DatabaseType.values()) {
            Path configPath = databasesPath.resolve(type.getConfigFileName());
            if (!Files.isRegularFile(configPath)) {
                throw new IllegalStateException("Missing database configuration file " + configPath + ".");
            }
            FilePermissionHardening.restrictFileToOwner(configPath, logger, type.name() + " database config");
            try {
                CommentedConfigurationNode node = YamlConfigurationLoader.builder().path(configPath).build().load();
                if (node.childrenMap().isEmpty()) {
                    throw new IllegalArgumentException("Database configuration " + configPath + " cannot be empty.");
                }
                loadedConfigs.put(type, node);
            } catch (IOException e) {
                throw new IllegalStateException("Error loading database configuration " + configPath, e);
            }
        }
        return new DatabaseConfigSnapshot(loadedConfigs);
    }

    /** Replaces the active database configuration only with a fully validated snapshot. */
    protected void applySnapshot(DatabaseConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "Database configuration snapshot cannot be null.");
        configMap = snapshot.configurations();
    }

    protected DatabaseConfigSnapshot currentSnapshot() {
        return new DatabaseConfigSnapshot(configMap);
    }

    /**
     * Returns a copied connection section from a candidate snapshot without applying that snapshot.
     * This is deliberately not an authorization API: callers use it only to construct a replacement
     * for a resource that already exists. Authorization is evaluated separately against the same
     * candidate snapshot before the reload is committed.
     */
    protected CommentedConfigurationNode getConfig(
            DatabaseConfigSnapshot snapshot,
            DatabaseType type,
            ConnectionIdentifier connectionIdentifier
    ) {
        Objects.requireNonNull(snapshot, "Database configuration snapshot cannot be null.");
        Objects.requireNonNull(type, "Database type cannot be null.");
        Objects.requireNonNull(connectionIdentifier, "Connection identifier cannot be null.");
        CommentedConfigurationNode configuration = snapshot.configurations().get(type);
        if (configuration == null) {
            return null;
        }
        CommentedConfigurationNode section = configuration.node(connectionIdentifier.value());
        return section.virtual() ? null : copyNode(section);
    }

    private boolean copyDefaultConfigFromResources(String resourcePath, File destinationFile) {
        try (InputStream in = openResource("databases/" + resourcePath)) {
            if (in == null) {
                return false;
            }
            Files.copy(in, destinationFile.toPath());
            FilePermissionHardening.restrictFileToOwner(destinationFile.toPath(), logger, resourcePath + " default config");
            logger.info("Copied default config: " + resourcePath);
            return true;
        } catch (IOException e) {
            logger.error("Failed to copy default config: " + resourcePath, e);
            return false;
        }
    }

    /**
     * The {@code default} section is a shipped, non-operational example. It is replaced on
     * every startup so its keys, comments and security guidance remain current. Named sections
     * are administrator-owned connections and are never modified by this upgrade path.
     */
    private boolean refreshBundledDefault(
            DatabaseType type,
            Path configPath,
            CommentedConfigurationNode configured
    ) throws IOException {
        CommentedConfigurationNode bundled = loadBundledConfig(type.getConfigFileName());
        CommentedConfigurationNode bundledDefault = bundled.node("default");
        if (bundledDefault.virtual()) {
            throw new IOException("Bundled " + type.getConfigFileName() + " has no default section.");
        }
        CommentedConfigurationNode configuredDefault = configured.node("default");
        if (configuredDefault.equals(bundledDefault)) {
            return false;
        }
        configuredDefault.from(bundledDefault);
        AtomicConfigurationWriter.save(configPath, configured, logger, type.name() + " database config");
        logger.info("Refreshed the bundled default example in " + type.getConfigFileName() + ".");
        return true;
    }

    private CommentedConfigurationNode loadBundledConfig(String resourcePath) throws IOException {
        if (resourceClassLoader.getResource("databases/" + resourcePath) == null) {
            throw new IOException("Bundled database config resource is missing: " + resourcePath);
        }
        return YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new InputStreamReader(
                        Objects.requireNonNull(openResource("databases/" + resourcePath)), StandardCharsets.UTF_8
                )))
                .build()
                .load();
    }

    private InputStream openResource(String resourcePath) throws IOException {
        URL url = resourceClassLoader.getResource(resourcePath);
        if (url == null) {
            return null;
        }
        URLConnection connection = url.openConnection();
        connection.setUseCaches(false);
        return connection.getInputStream();
    }

    /**
     * Returns the configuration section for the given connection identifier from the database configuration.
     *
     * @param type                 the DatabaseType
     * @param connectionIdentifier the identifier for a specific connection section
     * @return the corresponding CommentedConfigurationNode, or null if not found.
     */
    protected CommentedConfigurationNode getConfig(DatabaseType type, String connectionIdentifier) {
        return getConfig(type, ConnectionIdentifier.of(connectionIdentifier));
    }

    protected CommentedConfigurationNode getConfig(DatabaseType type, ConnectionIdentifier connectionIdentifier) {
        Objects.requireNonNull(type, "Database type cannot be null.");
        Objects.requireNonNull(connectionIdentifier, "Connection identifier cannot be null.");
        CommentedConfigurationNode config = configMap.get(type);
        if (config == null) {
            logger.warn("No configuration loaded for database type " + type.name());
            return null;
        }

        String identifierValue = connectionIdentifier.value();
        CommentedConfigurationNode section = config.node(identifierValue);
        if (section.virtual()) {
            logger.warn("No configuration section found for '" + identifierValue + "' in "
                    + type.getConfigFileName() + ". Available sections: " + describeAvailableSections(config));
            return null;
        }
        return copyNode(section);
    }

    /**
     * Resolves a connection only after its policy confirms that the calling plugin is allowed.
     * The configuration node is copied after authorization so callers cannot inspect another
     * plugin's configured credentials through this path.
     */
    protected AuthorizedConnection getAuthorizedConfig(
            DatabaseType type,
            ConnectionIdentifier connectionIdentifier,
            PluginId caller,
            Predicate<String> knownPlugin
    ) {
        Objects.requireNonNull(type, "Database type cannot be null.");
        Objects.requireNonNull(connectionIdentifier, "Connection identifier cannot be null.");
        Objects.requireNonNull(caller, "Caller plugin cannot be null.");
        Objects.requireNonNull(knownPlugin, "Known-plugin predicate cannot be null.");
        ResolvedConnection resolved = resolveAuthorizedConnection(
                configMap, type, connectionIdentifier, caller, knownPlugin
        );
        if (resolved == null) {
            return null;
        }
        return new AuthorizedConnection(copyNode(resolved.section()), resolved.accessPolicy());
    }

    /** Validates policy access without exposing or copying the connection credentials. */
    protected boolean isAuthorized(
            DatabaseType type,
            ConnectionIdentifier connectionIdentifier,
            PluginId caller,
            Predicate<String> knownPlugin
    ) {
        return resolveAuthorizedConnection(configMap, type, connectionIdentifier, caller, knownPlugin) != null;
    }

    /** Validates access against an unapplied configuration snapshot. */
    protected boolean isAuthorized(
            DatabaseConfigSnapshot snapshot,
            DatabaseType type,
            ConnectionIdentifier connectionIdentifier,
            PluginId caller,
            Predicate<String> knownPlugin
    ) {
        Objects.requireNonNull(snapshot, "Database configuration snapshot cannot be null.");
        return resolveAuthorizedConnection(snapshot.configurations(), type, connectionIdentifier, caller, knownPlugin) != null;
    }

    private ResolvedConnection resolveAuthorizedConnection(
            Map<DatabaseType, CommentedConfigurationNode> configurations,
            DatabaseType type,
            ConnectionIdentifier connectionIdentifier,
            PluginId caller,
            Predicate<String> knownPlugin
    ) {
        Objects.requireNonNull(type, "Database type cannot be null.");
        Objects.requireNonNull(connectionIdentifier, "Connection identifier cannot be null.");
        Objects.requireNonNull(caller, "Caller plugin cannot be null.");
        Objects.requireNonNull(knownPlugin, "Known-plugin predicate cannot be null.");
        CommentedConfigurationNode config = configurations.get(type);
        if (config == null) {
            logger.warn("No configuration loaded for database type " + type.name());
            return null;
        }

        String identifierValue = connectionIdentifier.value();
        CommentedConfigurationNode section = config.node(identifierValue);
        if (section.virtual()) {
            logger.warn("No configuration section found for '" + identifierValue + "' in "
                    + type.getConfigFileName() + ". Available sections: " + describeAvailableSections(config));
            return null;
        }
        String location = type.name() + "/" + identifierValue;
        ConnectionAccessPolicy policy = ConnectionAccessPolicy.from(section, location);
        policy.validateConfiguredPlugins(knownPlugin, location);
        policy.requireAccess(caller, location);
        return new ResolvedConnection(section, policy);
    }

    private static String describeAvailableSections(CommentedConfigurationNode config) {
        List<String> sections = config.childrenMap().keySet().stream()
                .map(String::valueOf)
                .sorted()
                .toList();
        if (sections.isEmpty()) {
            return "<none>";
        }
        return String.join(", ", sections);
    }

    protected record DatabaseConfigSnapshot(Map<DatabaseType, CommentedConfigurationNode> configurations) {
        protected DatabaseConfigSnapshot {
            EnumMap<DatabaseType, CommentedConfigurationNode> copies = new EnumMap<>(DatabaseType.class);
            configurations.forEach((type, node) -> copies.put(type, copyNode(node)));
            configurations = Map.copyOf(copies);
            if (configurations.size() != DatabaseType.values().length) {
                throw new IllegalArgumentException("Database configuration snapshot is incomplete.");
            }
        }

        int changedTypeCount(DatabaseConfigSnapshot other) {
            int changes = 0;
            for (DatabaseType type : DatabaseType.values()) {
                if (!configurations.get(type).equals(other.configurations.get(type))) {
                    changes++;
                }
            }
            return changes;
        }
    }

    private static CommentedConfigurationNode copyNode(CommentedConfigurationNode node) {
        return (CommentedConfigurationNode) node.copy();
    }

    protected record AuthorizedConnection(CommentedConfigurationNode config, ConnectionAccessPolicy accessPolicy) {
        protected AuthorizedConnection {
            config = copyNode(Objects.requireNonNull(config, "Connection configuration cannot be null."));
            Objects.requireNonNull(accessPolicy, "Connection access policy cannot be null.");
        }
    }

    private record ResolvedConnection(CommentedConfigurationNode section, ConnectionAccessPolicy accessPolicy) {
    }
}
