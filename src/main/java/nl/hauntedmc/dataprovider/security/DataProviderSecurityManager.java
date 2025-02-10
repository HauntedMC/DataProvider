package nl.hauntedmc.dataprovider.security;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.logger.DPLogger;
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
public class DataProviderSecurityManager {

    private static final String SECRET_FILE_NAME = "secret.yml";
    private static final String SECRET_KEY = "secret_token";

    private static String secret;
    private static final Set<String> authorizedPlugins = Collections.synchronizedSet(new HashSet<>());


    /**
     * Initializes the security manager.
     * <p>
     * This method loads (or creates) the secret file and must be called during your plugin's onEnable().
     * </p>
     */
    public static void initialize() {
        loadOrGenerateSecret();
        DPLogger.info("DataProviderSecurityManager initialized.");
    }

    private static void loadOrGenerateSecret() {
        File dataFolder = DataProvider.getInstance().getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        File secretFile = new File(dataFolder, SECRET_FILE_NAME);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(secretFile);
        if (config.contains(SECRET_KEY)) {
            secret = config.getString(SECRET_KEY);
            DPLogger.info("Loaded secret from " + secretFile.getAbsolutePath());
        } else {
            // Generate a new secret (using a UUID for randomness)
            secret = UUID.randomUUID().toString();
            config.set(SECRET_KEY, secret);
            try {
                config.save(secretFile);
                DPLogger.info("Generated new secret and saved to " + secretFile.getAbsolutePath());
            } catch (IOException e) {
                DPLogger.error("Failed to save secret file", e);
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
    public static boolean authorize(String pluginName, String token) {
        if (pluginName != null && secret != null && secret.equals(token)) {
            authorizedPlugins.add(pluginName);
            DPLogger.info("Plugin " + pluginName + " authorized successfully.");
            return true;
        }
        DPLogger.error("Failed to authorize plugin " + pluginName + ": Invalid token.");
        return false;
    }

    /**
     * Checks whether the given plugin (by name) is authorized.
     *
     * @param pluginName the plugin’s name
     * @return true if the plugin is authorized; false otherwise.
     */
    public static boolean isAuthorized(String pluginName) {
        return authorizedPlugins.contains(pluginName);
    }

}
