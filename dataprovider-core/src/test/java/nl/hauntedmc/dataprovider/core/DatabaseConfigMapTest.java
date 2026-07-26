package nl.hauntedmc.dataprovider.core;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConfigMapTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesExactIdentifierOnly() throws IOException {
        writeMySqlConfig("""
                modern:
                  host: modern-host
                  port: 3306
                """);

        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = new DatabaseConfigMap(tempDir, logger, getClass().getClassLoader());

        CommentedConfigurationNode node = configMap.getConfig(DatabaseType.MYSQL, "modern");
        assertNotNull(node);
        assertTrue("modern-host".equals(node.node("host").getString()));
        assertTrue(logger.warnMessages().isEmpty());
    }

    @Test
    void refreshesOnlyBundledDefaultAndPreservesUserNamedConnections() throws IOException {
        writeMySqlConfig("""
                default:
                  host: user-modified-example
                  obsolete_default_key: remove-me
                production:
                  access:
                    owner_plugin: ServerFeatures
                    shared_with: []
                  host: production-host
                  password: a-secret
                  user_extension: preserve-me
                """);

        DatabaseConfigMap configMap = new DatabaseConfigMap(
                tempDir,
                new RecordingLoggerAdapter(),
                getClass().getClassLoader()
        );

        CommentedConfigurationNode defaultConnection = configMap.getConfig(DatabaseType.MYSQL, "default");
        CommentedConfigurationNode production = configMap.getConfig(DatabaseType.MYSQL, "production");
        assertNotNull(defaultConnection);
        assertNotNull(production);
        assertEquals("localhost", defaultConnection.node("host").getString());
        assertTrue(defaultConnection.node("obsolete_default_key").virtual());
        assertEquals("production-host", production.node("host").getString());
        assertEquals("a-secret", production.node("password").getString());
        assertEquals("preserve-me", production.node("user_extension").getString());

        Path databaseFile = tempDir.resolve("databases").resolve(DatabaseType.MYSQL.getConfigFileName());
        String refreshedContents = Files.readString(databaseFile);
        RecordingLoggerAdapter secondStartupLogger = new RecordingLoggerAdapter();
        new DatabaseConfigMap(tempDir, secondStartupLogger, getClass().getClassLoader());
        assertEquals(refreshedContents, Files.readString(databaseFile));
        assertTrue(secondStartupLogger.infoMessages().stream().noneMatch(message ->
                message.contains("Refreshed the bundled default example in mysql.yml")));
    }

    @Test
    void reportsAvailableSectionsWhenIdentifierIsMissing() throws IOException {
        writeMySqlConfig("""
                alpha:
                  host: alpha-host
                beta:
                  host: beta-host
                """);

        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = new DatabaseConfigMap(tempDir, logger, getClass().getClassLoader());

        CommentedConfigurationNode node = configMap.getConfig(DatabaseType.MYSQL, "missing");
        assertNull(node);
        assertTrue(logger.warnMessages().stream().anyMatch(message ->
                message.contains("No configuration section found for 'missing'")
                        && message.contains("alpha")
                        && message.contains("beta")
        ));
    }

    @Test
    void rejectsUnknownConnectionIdentifiers() throws IOException {
        writeMySqlConfig("""
                default_credentials:
                  host: old-host
                """);

        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = new DatabaseConfigMap(tempDir, logger, getClass().getClassLoader());

        CommentedConfigurationNode node = configMap.getConfig(DatabaseType.MYSQL, "unknown");
        assertNull(node);
        assertTrue(logger.warnMessages().stream().anyMatch(message ->
                message.contains("No configuration section found for 'unknown'")
                        && message.contains("default_credentials")
        ));
    }

    @Test
    void authorizesOnlyTheOwnerAndExplicitlySharedPlugins() throws IOException {
        writeMySqlConfig("""
                shared:
                  access:
                    owner_plugin: ServerFeatures
                    shared_with:
                      - Economy
                  host: protected-host
                """);
        DatabaseConfigMap configMap = new DatabaseConfigMap(
                tempDir,
                new RecordingLoggerAdapter(),
                getClass().getClassLoader()
        );

        DatabaseConfigMap.AuthorizedConnection owner = configMap.getAuthorizedConfig(
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("shared"),
                PluginId.of("ServerFeatures"),
                plugin -> plugin.equals("serverfeatures") || plugin.equals("economy")
        );
        DatabaseConfigMap.AuthorizedConnection shared = configMap.getAuthorizedConfig(
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("shared"),
                PluginId.of("Economy"),
                plugin -> plugin.equals("serverfeatures") || plugin.equals("economy")
        );

        assertNotNull(owner);
        assertNotNull(shared);
        assertTrue(owner.accessPolicy().isExplicitlyShared());
        assertThrows(ConnectionAccessDeniedException.class, () -> configMap.getAuthorizedConfig(
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("shared"),
                PluginId.of("UntrustedPlugin"),
                plugin -> plugin.equals("serverfeatures") || plugin.equals("economy") || plugin.equals("untrustedplugin")
        ));
    }

    @Test
    void rejectsMissingPoliciesAndUnknownConfiguredPlugins() throws IOException {
        writeMySqlConfig("""
                missing-policy:
                  host: protected-host
                unknown-plugin:
                  access:
                    owner_plugin: NotInstalled
                    shared_with: []
                  host: protected-host
                """);
        DatabaseConfigMap configMap = new DatabaseConfigMap(
                tempDir,
                new RecordingLoggerAdapter(),
                getClass().getClassLoader()
        );

        assertThrows(InvalidConnectionAccessPolicyException.class, () -> configMap.getAuthorizedConfig(
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("missing-policy"),
                PluginId.of("ServerFeatures"),
                plugin -> plugin.equals("ServerFeatures")
        ));
        assertThrows(InvalidConnectionAccessPolicyException.class, () -> configMap.getAuthorizedConfig(
                DatabaseType.MYSQL,
                ConnectionIdentifier.of("unknown-plugin"),
                PluginId.of("ServerFeatures"),
                plugin -> plugin.equals("ServerFeatures")
        ));
    }

    @Test
    void returnsNullWhenNoConfigCanBeLoaded() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        ClassLoader emptyClassLoader = new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return null;
            }
        };

        DatabaseConfigMap configMap = new DatabaseConfigMap(tempDir, logger, emptyClassLoader);

        CommentedConfigurationNode node = configMap.getConfig(DatabaseType.MYSQL, "default");
        assertNull(node);
        assertTrue(logger.warnMessages().stream().anyMatch(message ->
                message.contains("No default config found for MYSQL")));
        assertTrue(logger.warnMessages().stream().anyMatch(message ->
                message.contains("No configuration loaded for database type MYSQL")));
    }

    @Test
    void logsYamlLoadFailureAndSkipsBrokenConfig() throws IOException {
        Path databasesDir = tempDir.resolve("databases");
        Files.createDirectories(databasesDir);
        Files.writeString(
                databasesDir.resolve(DatabaseType.MYSQL.getConfigFileName()),
                "invalid: [yaml",
                StandardCharsets.UTF_8
        );

        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = new DatabaseConfigMap(tempDir, logger, getClass().getClassLoader());

        CommentedConfigurationNode node = configMap.getConfig(DatabaseType.MYSQL, "default");
        assertNull(node);
        assertTrue(logger.errorMessages().stream().anyMatch(message -> message.contains("Failed to load config for MYSQL")));
    }

    @Test
    void rejectsCandidateSnapshotWhenAnyDatabaseFileIsMalformed() throws IOException {
        writeMySqlConfig("""
                default:
                  host: active-host
                """);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = new DatabaseConfigMap(tempDir, logger, getClass().getClassLoader());
        Path redisConfig = tempDir.resolve("databases").resolve(DatabaseType.REDIS.getConfigFileName());
        Files.writeString(redisConfig, "invalid: [yaml", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, configMap::loadSnapshot);

        CommentedConfigurationNode activeConfig = configMap.getConfig(DatabaseType.MYSQL, "default");
        assertNotNull(activeConfig);
        assertTrue("localhost".equals(activeConfig.node("host").getString()));
    }

    private void writeMySqlConfig(String content) throws IOException {
        Path databasesDir = tempDir.resolve("databases");
        Files.createDirectories(databasesDir);
        Files.writeString(
                databasesDir.resolve(DatabaseType.MYSQL.getConfigFileName()),
                content,
                StandardCharsets.UTF_8
        );
    }
}
