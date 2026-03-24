package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.DataProvider;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.loader.ConfigurationLoader;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SecurityManager {

    private static final String SECRET_FILE_NAME = "secret.yml";
    private static final String SECRET_KEY = "secret_token";
    private static final StackWalker CALLER_STACK_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private String secret;
    private final Map<String, ClassLoader> authorizedPlugins = new ConcurrentHashMap<>();
    private final Map<ClassLoader, String> authorizedCallers = new ConcurrentHashMap<>();

    private final Path secretFile;
    private final ConfigurationLoader<CommentedConfigurationNode> loader;

    public SecurityManager() {
        // Ensure the plugin data folder exists.
        Path dataFolder = DataProvider.getDataPath();
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException e) {
            DataProvider.getLogger().error("Failed to create plugin data folder", e);
        }
        // Define the secret file path and the YAML loader.
        this.secretFile = dataFolder.resolve(SECRET_FILE_NAME);
        this.loader = YamlConfigurationLoader.builder().path(secretFile).build();
        initialize();
        DataProvider.getLogger().info("DataProviderSecurityManager initialized.");
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
            DataProvider.getLogger().error("Error loading secret configuration", e);
            return;
        }

        // If the secret is missing, generate and save a new one.
        String loadedSecret = config.node(SECRET_KEY).getString();
        if (loadedSecret == null || loadedSecret.isBlank()) {
            secret = UUID.randomUUID().toString();
            try {
                config.node(SECRET_KEY).set(secret);
            } catch (SerializationException e) {
                DataProvider.getLogger().error("Failed to store generated secret token", e);
                return;
            }
            try {
                loader.save(config);
                applySecretFilePermissions();
                DataProvider.getLogger().info("Generated new DataProvider secret.");
            } catch (IOException e) {
                DataProvider.getLogger().error("Failed to save secret file", e);
            }
        } else {
            secret = loadedSecret;
            applySecretFilePermissions();
            DataProvider.getLogger().info("Loaded DataProvider secret.");
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
        if (pluginName == null || pluginName.isBlank()) {
            DataProvider.getLogger().error("Failed to authorize plugin: plugin name was empty.");
            return false;
        }
        if (token == null || token.isBlank()) {
            DataProvider.getLogger().error("Failed to authorize plugin " + pluginName + ": token was empty.");
            return false;
        }
        if (secret == null || secret.isBlank()) {
            DataProvider.getLogger().error("Failed to authorize plugin " + pluginName + ": security secret is unavailable.");
            return false;
        }
        if (!secureEquals(secret, token)) {
            DataProvider.getLogger().error("Failed to authorize plugin " + pluginName + ": invalid token.");
            return false;
        }

        ClassLoader callerClassLoader = getExternalCallerClassLoader();
        if (callerClassLoader == null) {
            DataProvider.getLogger().error("Failed to authorize plugin " + pluginName + ": could not resolve caller class loader.");
            return false;
        }

        String existingPluginNameForCaller = authorizedCallers.get(callerClassLoader);
        if (existingPluginNameForCaller != null && !existingPluginNameForCaller.equals(pluginName)) {
            DataProvider.getLogger().error(
                    "Failed to authorize plugin " + pluginName + ": caller already authorized as " + existingPluginNameForCaller + "."
            );
            return false;
        }

        ClassLoader alreadyBound = authorizedPlugins.putIfAbsent(pluginName, callerClassLoader);
        if (alreadyBound != null && alreadyBound != callerClassLoader) {
            DataProvider.getLogger().error(
                    "Failed to authorize plugin " + pluginName + ": plugin name already bound to another class loader."
            );
            return false;
        }
        authorizedCallers.putIfAbsent(callerClassLoader, pluginName);

        DataProvider.getLogger().info("Plugin " + pluginName + " authorized successfully.");
        return true;
    }

    /**
     * Checks whether the given plugin (by name) is authorized.
     *
     * @param pluginName the plugin’s name
     * @return true if the plugin is authorized; false otherwise.
     */
    public boolean isAuthorized(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) {
            return false;
        }

        ClassLoader callerClassLoader = getExternalCallerClassLoader();
        if (callerClassLoader == null) {
            return false;
        }

        ClassLoader authorizedClassLoader = authorizedPlugins.get(pluginName);
        return authorizedClassLoader != null && authorizedClassLoader == callerClassLoader;
    }

    public void revokeAuthorization(String pluginName) {
        if (pluginName == null || pluginName.isBlank()) {
            return;
        }
        ClassLoader removedClassLoader = authorizedPlugins.remove(pluginName);
        if (removedClassLoader != null) {
            authorizedCallers.remove(removedClassLoader, pluginName);
        }
    }

    private static boolean secureEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8)
        );
    }

    private ClassLoader getExternalCallerClassLoader() {
        ClassLoader ownClassLoader = SecurityManager.class.getClassLoader();
        return CALLER_STACK_WALKER.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .map(Class::getClassLoader)
                .filter(Objects::nonNull)
                .filter(classLoader -> classLoader != ownClassLoader)
                .findFirst()
                .orElse(null));
    }

    private void applySecretFilePermissions() {
        try {
            if (!Files.exists(secretFile)) {
                return;
            }
            PosixFileAttributeView posixView = Files.getFileAttributeView(secretFile, PosixFileAttributeView.class);
            if (posixView == null) {
                return;
            }
            Files.setPosixFilePermissions(secretFile,
                    java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX file systems (e.g. Windows) do not support chmod-style permissions.
        } catch (IOException e) {
            DataProvider.getLogger().warn("Could not tighten permissions for secret file: " + e.getMessage());
        }
    }
}
