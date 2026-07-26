package nl.hauntedmc.dataprovider.core.config;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void constructorCreatesConfigAndInjectsMissingDefaults() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();

        ConfigHandler handler = new ConfigHandler(tempDir, logger);

        Path configFile = tempDir.resolve("config.yml");
        assertTrue(Files.exists(configFile));
        assertTrue(handler.isDatabaseTypeEnabled(DatabaseType.MYSQL));
        assertTrue(handler.isDatabaseTypeEnabled(DatabaseType.MONGODB));
        assertTrue(handler.isDatabaseTypeEnabled(DatabaseType.REDIS));
        assertTrue(handler.isDatabaseTypeEnabled(DatabaseType.REDIS_MESSAGING));
        assertEquals("validate", handler.getOrmSchemaMode());
    }

    @Test
    void upgradesMissingKeysAndPrunesObsoleteKeysWithoutOverwritingConfiguredValues() throws IOException {
        writeConfig("""
                orm:
                  schema_mode: update
                execution:
                  lanes:
                    relational:
                      workers: 3
                obsolete_setting: true
                obsolete_root: remove-me
                """);

        ConfigHandler handler = new ConfigHandler(tempDir, new RecordingLoggerAdapter());

        assertEquals("update", handler.getOrmSchemaMode());
        assertEquals(3, handler.getConfig().node("execution", "lanes", "relational", "workers").getInt());
        assertEquals(2048, handler.getConfig().node("execution", "lanes", "relational", "queue_capacity").getInt());
        assertTrue(handler.getConfig().node("execution", "lanes", "messaging", "workers").getInt() > 0);
        assertTrue(handler.getConfig().node("databases", "redis_messaging", "enabled").getBoolean());
        assertTrue(handler.getConfig().node("obsolete_root").virtual());
        assertTrue(handler.getConfig().node("execution", "obsolete_setting").virtual());

        String upgradedContents = Files.readString(tempDir.resolve("config.yml"));
        RecordingLoggerAdapter secondStartupLogger = new RecordingLoggerAdapter();
        new ConfigHandler(tempDir, secondStartupLogger);
        assertEquals(upgradedContents, Files.readString(tempDir.resolve("config.yml")));
        assertFalse(secondStartupLogger.infoMessages().stream().anyMatch(message ->
                message.contains("Reconciled config.yml")));
    }

    @Test
    void rejectsInvalidOrmModeDuringSnapshotValidation() throws IOException {
        writeConfig("""
                databases:
                  mysql:
                    enabled: false
                orm:
                  schema_mode: bad-mode
                """);

        assertThrows(IllegalArgumentException.class, () -> new ConfigHandler(tempDir, new RecordingLoggerAdapter()));
    }

    @Test
    void normalizesSupportedOrmModeValues() throws IOException {
        writeConfig("""
                orm:
                  schema_mode: "  UPDATE "
                """);

        ConfigHandler handler = new ConfigHandler(tempDir, new RecordingLoggerAdapter());
        assertEquals("update", handler.getOrmSchemaMode());
    }

    @Test
    void constructorRejectsNullArguments() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        assertThrows(NullPointerException.class, () -> new ConfigHandler(null, logger));
        assertThrows(NullPointerException.class, () -> new ConfigHandler(tempDir, null));
    }

    @Test
    void reloadConfigReadsLatestFileState() throws IOException {
        writeConfig("""
                databases:
                  mysql:
                    enabled: false
                """);

        ConfigHandler handler = new ConfigHandler(tempDir, new RecordingLoggerAdapter());
        assertFalse(handler.isDatabaseTypeEnabled(DatabaseType.MYSQL));

        writeConfig("""
                databases:
                  mysql:
                    enabled: true
                orm:
                  schema_mode: none
                """);
        handler.reloadConfig();

        assertTrue(handler.isDatabaseTypeEnabled(DatabaseType.MYSQL));
        assertEquals("none", handler.getOrmSchemaMode());
    }

    @Test
    void rejectedReloadKeepsPreviouslyActiveSnapshot() throws IOException {
        writeConfig("""
                databases:
                  mysql:
                    enabled: false
                """);
        ConfigHandler handler = new ConfigHandler(tempDir, new RecordingLoggerAdapter());

        writeConfig("""
                databases:
                  mysql:
                    enabled: true
                orm:
                  schema_mode: invalid
                """);

        assertThrows(IllegalArgumentException.class, handler::reloadConfig);
        assertFalse(handler.isDatabaseTypeEnabled(DatabaseType.MYSQL));
        assertEquals("validate", handler.getOrmSchemaMode());
    }

    private void writeConfig(String content) throws IOException {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("config.yml"), content, StandardCharsets.UTF_8);
    }
}
