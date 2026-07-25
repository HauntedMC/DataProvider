package nl.hauntedmc.dataprovider.core;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.core.api.DefaultDataProviderApi;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.core.identity.CallerContext;
import nl.hauntedmc.dataprovider.core.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityRegistry;
import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import nl.hauntedmc.dataprovider.exception.ProviderClosedException;
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
        CallerContextResolver resolver = resolver(classLoader);

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
            CallerContextResolver resolver = resolver(classLoader);
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

    @Test
    void staleApiReferenceFailsAfterShutdownAndNewProviderStartsClean() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        ClassLoader classLoader = getClass().getClassLoader();
        CallerContextResolver resolver = resolver(classLoader);

        DataProvider firstProvider = new DataProvider(logger, tempDir.resolve("data-first"), classLoader, resolver);
        DataProviderAPI staleApi = new DefaultDataProviderApi(firstProvider.getDataProviderHandler()).forPlugin(this);

        firstProvider.shutdownAllDatabases();

        ProviderClosedException staleFailure = assertThrows(
                ProviderClosedException.class,
                () -> staleApi.registerDatabaseOrThrow(DatabaseType.MYSQL, "default")
        );
        assertTrue(staleFailure.getMessage().contains("no longer available"));

        DataProvider secondProvider = new DataProvider(logger, tempDir.resolve("data-second"), classLoader, resolver);
        DataProviderAPI freshApi = new DefaultDataProviderApi(secondProvider.getDataProviderHandler()).forPlugin(this);
        assertThrows(
                nl.hauntedmc.dataprovider.exception.DataProviderRegistrationException.class,
                () -> freshApi.requireRegisteredDatabase(DatabaseType.MYSQL, "default")
        );
        secondProvider.shutdownAllDatabases();
    }

    private static CallerContextResolver resolver(ClassLoader classLoader) {
        PluginIdentityRegistry identities = new PluginIdentityRegistry();
        PluginIdentity identity = identities.register("plugin", classLoader);
        return new CallerContextResolver() {
            @Override public CallerContext resolveCaller() { return new CallerContext("plugin", classLoader); }
            @Override public PluginIdentity issueIdentity(Object plugin) { return identity; }
            @Override public boolean isIdentityActive(PluginIdentity candidate) { return identities.isActive(candidate); }
            @Override public boolean isKnownPlugin(String pluginId) { return identities.isKnownPlugin(pluginId); }
        };
    }
}
