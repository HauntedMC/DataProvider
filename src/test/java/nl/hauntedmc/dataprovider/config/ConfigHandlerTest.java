package nl.hauntedmc.dataprovider.config;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.testutil.RecordingLoggerAdapter;
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
    void respectsConfiguredDatabaseFlagsAndFallsBackForInvalidOrmMode() throws IOException {
        writeConfig("""
                databases:
                  mysql:
                    enabled: false
                orm:
                  schema_mode: bad-mode
                """);

        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        ConfigHandler handler = new ConfigHandler(tempDir, logger);

        assertFalse(handler.isDatabaseTypeEnabled(DatabaseType.MYSQL));
        assertTrue(handler.isDatabaseTypeEnabled(DatabaseType.MONGODB));
        assertEquals("validate", handler.getOrmSchemaMode());
        assertTrue(logger.warnMessages().stream().anyMatch(m -> m.contains("Invalid orm.schema_mode")));
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

    private void writeConfig(String content) throws IOException {
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("config.yml"), content, StandardCharsets.UTF_8);
    }
}
