package nl.hauntedmc.dataprovider;

import nl.hauntedmc.dataprovider.internal.identity.CallerContext;
import nl.hauntedmc.dataprovider.internal.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.testutil.RecordingLoggerAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void constructorRejectsNullArguments() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        Path dataDir = tempDir.resolve("data");
        ClassLoader classLoader = getClass().getClassLoader();
        CallerContextResolver resolver = () -> new CallerContext("plugin", classLoader);

        assertThrows(NullPointerException.class, () -> new DataProvider(null, dataDir, classLoader, resolver));
        assertThrows(NullPointerException.class, () -> new DataProvider(logger, null, classLoader, resolver));
        assertThrows(NullPointerException.class, () -> new DataProvider(logger, dataDir, null, resolver));
        assertThrows(NullPointerException.class, () -> new DataProvider(logger, dataDir, classLoader, null));
    }

    @Test
    void exposesCoreComponentsAndLoadsResourcesFromClassLoader() throws IOException {
        Path resourceRoot = Files.createDirectories(tempDir.resolve("resources"));
        Files.writeString(resourceRoot.resolve("custom-resource.txt"), "hello", StandardCharsets.UTF_8);

        try (URLClassLoader classLoader = new URLClassLoader(
                new java.net.URL[]{resourceRoot.toUri().toURL()},
                getClass().getClassLoader()
        )) {
            RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
            CallerContextResolver resolver = () -> new CallerContext("plugin", classLoader);
            DataProvider provider = new DataProvider(logger, tempDir.resolve("data"), classLoader, resolver);

            assertEquals(logger, provider.getLogger());
            assertNotNull(provider.getConfigHandler());
            assertNotNull(provider.getDataProviderHandler());
            assertTrue(provider.getDataPath().endsWith("data"));

            try (InputStream in = provider.getResource("custom-resource.txt")) {
                assertNotNull(in);
                assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }

            assertNull(provider.getResource("missing-resource.txt"));
            assertThrows(IllegalArgumentException.class, () -> provider.getResource(null));
        }
    }
}
