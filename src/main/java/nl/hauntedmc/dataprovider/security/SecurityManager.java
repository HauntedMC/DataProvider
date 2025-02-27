package nl.hauntedmc.dataprovider.security;

import nl.hauntedmc.dataprovider.DataProvider;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * DataProviderSecurityManager handles plugin authentication for the DataProvider API.
 * It loads (or creates) a secret token from a file named secret.yml in the plugin’s data folder.
 * Plugins must authenticate by providing this token.
 */
public class SecurityManager {

    private final String SECRET_FILE_NAME = "secret.yml";
    private final String SECRET_KEY = "secret_token";
    private final DataProvider plugin;

    private String secret;
    private final Set<String> authorizedPlugins = Collections.synchronizedSet(new HashSet<>());

    public SecurityManager(DataProvider plugin) {
        this.plugin = plugin;
        initialize();
        plugin.getLogger().info("DataProviderSecurityManager initialized.");
    }

    private void initialize() {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File secretFile = new File(dataFolder, SECRET_FILE_NAME);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(secretFile);
        if (config.contains(SECRET_KEY)) {
            secret = config.getString(SECRET_KEY);
            plugin.getLogger().info("Loaded secret from " + secretFile.getAbsolutePath());
        } else {
            // Generate a new secret (using a UUID for randomness)
            secret = UUID.randomUUID().toString();
            config.set(SECRET_KEY, secret);
            try {
                config.save(secretFile);
                plugin.getLogger().info("Generated new secret and saved to " + secretFile.getAbsolutePath());
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to save secret file", e);
            }
        }
    }

    /**
     * Authenticates a plugin by checking its provided token.
     * If the token matches the secret, the plugin is marked as authorized.
     *
     * @param pluginName the name of the plugin attempting authentication
     * @param token      the token provided by the plugin
     * @return true if authentication is successful; false otherwise.
     */
    public boolean authorize(String pluginName, String token) {
        if (pluginName != null && secret != null && secret.equals(token)) {
            authorizedPlugins.add(pluginName);
            plugin.getLogger().info("Plugin " + pluginName + " authorized successfully.");
            return true;
        }
        plugin.getLogger().severe("Failed to authorize plugin " + pluginName + ": Invalid token.");
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
