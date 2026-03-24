package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.testutil.RecordingLoggerAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConfigMapTest {

    @TempDir
    Path tempDir;

    @Test
    void fallsBackFromDefaultToLegacyIdentifier() throws IOException {
        writeMySqlConfig("""
                default_credentials:
                  host: legacy-host
                  port: 3306
                """);

        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = new DatabaseConfigMap(tempDir, logger, getClass().getClassLoader());

        CommentedConfigurationNode node = configMap.getConfig(DatabaseType.MYSQL, "default");
        assertNotNull(node);
        assertEquals("legacy-host", node.node("host").getString());
        assertTrue(logger.warnMessages().stream().anyMatch(message ->
                message.contains("Connection identifier 'default'")
                        && message.contains("default_credentials")
        ));
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
    void exactIdentifierTakesPrecedenceOverLegacyAlias() throws IOException {
        writeMySqlConfig("""
                default:
                  host: modern-host
                default_credentials:
                  host: legacy-host
                """);

        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = new DatabaseConfigMap(tempDir, logger, getClass().getClassLoader());

        CommentedConfigurationNode node = configMap.getConfig(DatabaseType.MYSQL, "default");
        assertNotNull(node);
        assertEquals("modern-host", node.node("host").getString());
        assertTrue(logger.warnMessages().stream().noneMatch(message ->
                message.contains("Connection identifier 'default'")
                        && message.contains("default_credentials")
        ));
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
