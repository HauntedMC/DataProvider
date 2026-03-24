package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class DatabaseConfigMap {

    private static final String DEFAULT_CONNECTION_IDENTIFIER = "default";
    private static final String LEGACY_DEFAULT_CONNECTION_IDENTIFIER = "default_credentials";
    private static final Map<String, String> CONNECTION_IDENTIFIER_ALIASES = Map.of(
            DEFAULT_CONNECTION_IDENTIFIER, LEGACY_DEFAULT_CONNECTION_IDENTIFIER,
            LEGACY_DEFAULT_CONNECTION_IDENTIFIER, DEFAULT_CONNECTION_IDENTIFIER
    );

    private final Path dataPath;
    private final ILoggerAdapter logger;
    private final ClassLoader resourceClassLoader;
    private final Map<DatabaseType, CommentedConfigurationNode> configMap = new HashMap<>();

    protected DatabaseConfigMap(Path dataPath, ILoggerAdapter logger, ClassLoader resourceClassLoader) {
        this.dataPath = Objects.requireNonNull(dataPath, "Data path cannot be null.");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
        this.resourceClassLoader = Objects.requireNonNull(resourceClassLoader, "Resource class loader cannot be null.");
        initialize();
    }

    private void initialize() {
        configMap.clear();
        File databasesFolder = new File(String.valueOf(dataPath), "databases");
        if (!databasesFolder.exists() && !databasesFolder.mkdirs()) {
            logger.warn("Failed to create databases folder at: " + databasesFolder.getAbsolutePath());
        } else {
            logger.info("Databases folder located at: " + databasesFolder.getAbsolutePath());
        }

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
                ConfigurationLoader<CommentedConfigurationNode> loader = YamlConfigurationLoader.builder()
                        .path(path)
                        .build();
                try {
                    CommentedConfigurationNode node = loader.load();
                    configMap.put(type, node);
                } catch (IOException e) {
                    logger.error("Failed to load config for " + type.name(), e);
                }
            }
        }
        logger.info("Loaded " + configMap.size() + " database configurations.");
    }

    private boolean copyDefaultConfigFromResources(String resourcePath, File destinationFile) {
        try (InputStream in = openResource("databases/" + resourcePath)) {
            if (in == null) {
                return false;
            }
            Files.copy(in, destinationFile.toPath());
            logger.info("Copied default config: " + resourcePath);
            return true;
        } catch (IOException e) {
            logger.error("Failed to copy default config: " + resourcePath, e);
            return false;
        }
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
        CommentedConfigurationNode config = configMap.get(type);
        if (config == null) {
            logger.warn("No configuration loaded for database type " + type.name());
            return null;
        }

        CommentedConfigurationNode section = resolveConnectionSection(config, type, connectionIdentifier);
        if (section != null) {
            return section;
        }

        logger.warn("No configuration section found for '" + connectionIdentifier + "' in " + type.getConfigFileName()
                + ". Available sections: " + describeAvailableSections(config));
        return null;
    }

    private CommentedConfigurationNode resolveConnectionSection(
            CommentedConfigurationNode config,
            DatabaseType type,
            String connectionIdentifier
    ) {
        CommentedConfigurationNode section = config.node(connectionIdentifier);
        if (!section.virtual()) {
            return section;
        }

        String alias = CONNECTION_IDENTIFIER_ALIASES.get(connectionIdentifier);
        if (alias == null) {
            return null;
        }

        CommentedConfigurationNode aliasSection = config.node(alias);
        if (aliasSection.virtual()) {
            return null;
        }

        logger.warn("Connection identifier '" + connectionIdentifier + "' not found in " + type.getConfigFileName()
                + ". Falling back to legacy alias '" + alias + "'.");
        return aliasSection;
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
}
