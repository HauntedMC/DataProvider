package nl.hauntedmc.dataprovider.security;

import nl.hauntedmc.dataprovider.DataProviderApp;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class SecurityManager {

    private static final String SECRET_FILE_NAME = "secret.yml";
    private static final String SECRET_KEY = "secret_token";

    private String secret;
    private final Set<String> authorizedPlugins = Collections.synchronizedSet(new HashSet<>());

    private final Path secretFile;
    private final ConfigurationLoader<CommentedConfigurationNode> loader;

    public SecurityManager() {
        // Ensure the plugin data folder exists.
        Path dataFolder = DataProviderApp.getDataPath();
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            DataProviderApp.getLogger().error("Failed to create plugin data folder", e);
        }
        // Define the secret file path and the YAML loader.
        this.secretFile = dataFolder.resolve(SECRET_FILE_NAME);
        this.loader = YamlConfigurationLoader.builder().path(secretFile).build();
        initialize();
        DataProviderApp.getLogger().info("DataProviderSecurityManager initialized.");
    }

    private void initialize() {
        CommentedConfigurationNode config;
        try {
            if (Files.notExists(secretFile)) {
                // Create a new configuration node if the file does not exist.
                config = loader.createNode();
            } else {
                // Otherwise, load the existing configuration.
                config = loader.load();
            }
        } catch (IOException e) {
            DataProviderApp.getLogger().error("Error loading secret configuration", e);
            return;
        }

        // If the secret is missing, generate and save a new one.
        if (config.node(SECRET_KEY).virtual()) {
            secret = UUID.randomUUID().toString();
            try {
                config.node(SECRET_KEY).set(secret);
            } catch (SerializationException e) {
                throw new RuntimeException(e);
            }
            try {
                loader.save(config);
                DataProviderApp.getLogger().info("Generated new secret and saved to " + secretFile.toAbsolutePath());
            } catch (IOException e) {
                DataProviderApp.getLogger().error("Failed to save secret file", e);
            }
        } else {
            secret = config.node(SECRET_KEY).getString();
            DataProviderApp.getLogger().info("Loaded secret from " + secretFile.toAbsolutePath());
        }
    }

    /**
     * Authenticates a plugin by comparing its provided token with the stored secret.
     * If they match, the plugin is marked as authorized.
     *
     * @param pluginName the name of the plugin attempting authentication
     * @param token      the token provided by the plugin
     * @return true if authentication is successful; false otherwise.
     */
    public boolean authorize(String pluginName, String token) {
        if (pluginName != null && secret != null && secret.equals(token)) {
            authorizedPlugins.add(pluginName);
            DataProviderApp.getLogger().info("Plugin " + pluginName + " authorized successfully.");
            return true;
        }
        DataProviderApp.getLogger().error("Failed to authorize plugin " + pluginName + ": Invalid token.");
        return false;
    }

    /**
     * Checks whether the given plugin (by name) is authorized.
     *
     * @param pluginName the plugin’s name
     * @return true if the plugin is authorized; false otherwise.
     */
    public boolean isAuthorized(String pluginName) {
        return authorizedPlugins.contains(pluginName);
    }
}
