package nl.hauntedmc.dataprovider.core.config;

import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicConfigurationWriterTest {

    @TempDir
    Path tempDirectory;

    @Test
    void writesReadableBlockYamlAndReplacesExistingContent() throws Exception {
        Path destination = tempDirectory.resolve("config.yml");
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        CommentedConfigurationNode first = CommentedConfigurationNode.root();
        first.node("databases", "mysql", "host").raw("localhost");
        first.node("databases", "mysql", "port").raw(3306);

        AtomicConfigurationWriter.save(destination, first, logger, "configuration");

        String firstContents = Files.readString(destination);
        assertTrue(firstContents.contains("host: localhost"));
        assertTrue(firstContents.contains("port: 3306"));
        assertFalse(firstContents.contains("{"));

        CommentedConfigurationNode replacement = CommentedConfigurationNode.root();
        replacement.node("databases", "redis", "host").raw("redis.internal");
        AtomicConfigurationWriter.save(destination, replacement, logger, "configuration");

        String replacementContents = Files.readString(destination);
        assertTrue(replacementContents.contains("redis.internal"));
        assertFalse(replacementContents.contains("localhost"));
        assertNoTemporaryFiles(destination);
        assertTrue(logger.warnMessages().isEmpty());
    }

    @Test
    void resultingFileCanBeLoadedBackWithoutTypeLossForScalarValues() throws Exception {
        Path destination = tempDirectory.resolve("roundtrip.yml");
        CommentedConfigurationNode configuration = CommentedConfigurationNode.root();
        configuration.node("enabled").raw(true);
        configuration.node("workers").raw(8);
        configuration.node("timeout_ms").raw(1_500L);
        configuration.node("name").raw("primary");

        AtomicConfigurationWriter.save(
                destination, configuration, new RecordingLoggerAdapter(), "round-trip configuration");
        CommentedConfigurationNode loaded = YamlConfigurationLoader.builder().path(destination).build().load();

        assertTrue(loaded.node("enabled").getBoolean());
        assertEquals(8, loaded.node("workers").getInt());
        assertEquals(1_500L, loaded.node("timeout_ms").getLong());
        assertEquals("primary", loaded.node("name").getString());
    }

    @Test
    void concurrentWritersNeverExposeTruncatedOrUnparseableYaml() throws Exception {
        Path destination = tempDirectory.resolve("concurrent.yml");
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        int writerCount = 24;

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> writes = new ArrayList<>();
            for (int writer = 0; writer < writerCount; writer++) {
                int value = writer;
                writes.add(executor.submit(() -> {
                    CommentedConfigurationNode configuration = CommentedConfigurationNode.root();
                    configuration.node("writer").raw(value);
                    configuration.node("payload").raw("x".repeat(2_000));
                    try {
                        AtomicConfigurationWriter.save(destination, configuration, logger, "concurrent configuration");
                    } catch (IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                }));
            }
            for (var write : writes) {
                write.get(10, TimeUnit.SECONDS);
            }
        }

        CommentedConfigurationNode loaded = YamlConfigurationLoader.builder().path(destination).build().load();
        int finalWriter = loaded.node("writer").getInt(-1);
        assertTrue(finalWriter >= 0 && finalWriter < writerCount);
        assertEquals("x".repeat(2_000), loaded.node("payload").getString());
        assertNoTemporaryFiles(destination);
    }

    @Test
    void rejectsDestinationsWithoutAParentDirectoryBeforeWriting() {
        IOException failure = assertThrows(IOException.class, () -> AtomicConfigurationWriter.save(
                Path.of("config.yml"),
                CommentedConfigurationNode.root(),
                new RecordingLoggerAdapter(),
                "configuration"
        ));

        assertTrue(failure.getMessage().contains("no parent directory"));
    }

    @Test
    void missingParentDirectoryLeavesNoPartialDestination() {
        Path missingDirectory = tempDirectory.resolve("missing");
        Path destination = missingDirectory.resolve("config.yml");

        assertThrows(IOException.class, () -> AtomicConfigurationWriter.save(
                destination,
                CommentedConfigurationNode.root(),
                new RecordingLoggerAdapter(),
                "configuration"
        ));

        assertFalse(Files.exists(destination));
        assertFalse(Files.exists(missingDirectory));
    }

    private static void assertNoTemporaryFiles(Path destination) throws IOException {
        try (var files = Files.list(destination.getParent())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(destination.getFileName().toString())
                    && path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
