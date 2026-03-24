package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.testutil.RecordingLoggerAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConfigMapTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesExactIdentifierOnly() throws IOException {
        writeMySqlConfig("""
                default:
                  host: modern-host
                  port: 3306
                """);

        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = new DatabaseConfigMap(tempDir, logger, getClass().getClassLoader());

        CommentedConfigurationNode node = configMap.getConfig(DatabaseType.MYSQL, "default");
        assertNotNull(node);
        assertTrue("modern-host".equals(node.node("host").getString()));
        assertTrue(logger.warnMessages().isEmpty());
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
    void doesNotFallbackToLegacyIdentifiers() throws IOException {
        writeMySqlConfig("""
                default_credentials:
                  host: legacy-host
                """);

        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = new DatabaseConfigMap(tempDir, logger, getClass().getClassLoader());

        CommentedConfigurationNode node = configMap.getConfig(DatabaseType.MYSQL, "default");
        assertNull(node);
        assertTrue(logger.warnMessages().stream().anyMatch(message ->
                message.contains("No configuration section found for 'default'")
                        && message.contains("default_credentials")
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
