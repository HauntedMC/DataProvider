package nl.hauntedmc.dataprovider.core;

import nl.hauntedmc.dataprovider.core.config.ConfigHandler;
import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import nl.hauntedmc.dataprovider.database.DataAccess;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataProviderRegistryConcurrencyTest {

    @Test
    void concurrentRegistrationCreatesOnePhysicalProviderPerKey() throws Exception {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler config = enabledConfig();
        AtomicInteger factoryCalls = new AtomicInteger();
        BlockingProvider provider = new BlockingProvider();
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, ConnectionIdentifier.of("default")))
                .thenAnswer(ignored -> {
                    factoryCalls.incrementAndGet();
                    return provider;
                });
        DataProviderRegistry registry = new DataProviderRegistry(factory, config, new RecordingLoggerAdapter());

        ExecutorService executor = Executors.newFixedThreadPool(24);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<DatabaseProvider>> registrations = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                registrations.add(CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return registry.registerDatabase("plugin", "scope", DatabaseType.MYSQL, "default");
                }, executor));
            }
            start.countDown();
            provider.allowConnect.countDown();

            for (CompletableFuture<DatabaseProvider> registration : registrations) {
                assertSame(provider, registration.get(10, TimeUnit.SECONDS));
            }
            assertEquals(1, factoryCalls.get());
            assertEquals(1, provider.connectCalls.get());
            assertEquals(100, registry.getActiveDatabaseReferenceCounts().values().iterator().next());
        } finally {
            registry.shutdownAllDatabases();
            executor.shutdownNow();
        }
        assertEquals(1, provider.disconnectCalls.get());
    }

    @Test
    void differentKeysConnectWithoutGlobalSerialization() throws Exception {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler config = enabledConfig();
        CountDownLatch bothConnecting = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        BlockingProvider first = new BlockingProvider(bothConnecting, release);
        BlockingProvider second = new BlockingProvider(bothConnecting, release);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, ConnectionIdentifier.of("first"))).thenReturn(first);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, ConnectionIdentifier.of("second"))).thenReturn(second);
        DataProviderRegistry registry = new DataProviderRegistry(factory, config, new RecordingLoggerAdapter());
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<DatabaseProvider> firstRegistration = CompletableFuture.supplyAsync(
                    () -> registry.registerDatabase("plugin", "scope", DatabaseType.MYSQL, "first"), executor);
            CompletableFuture<DatabaseProvider> secondRegistration = CompletableFuture.supplyAsync(
                    () -> registry.registerDatabase("plugin", "scope", DatabaseType.MYSQL, "second"), executor);

            assertTrue(bothConnecting.await(5, TimeUnit.SECONDS),
                    "Both physical connections should start concurrently.");
            release.countDown();
            assertSame(first, firstRegistration.get(5, TimeUnit.SECONDS));
            assertSame(second, secondRegistration.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            registry.shutdownAllDatabases();
            executor.shutdownNow();
        }
    }

    @Test
    void failedConnectionCanBeRetriedWithAReplacementProvider() {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler config = enabledConfig();
        BlockingProvider failed = new BlockingProvider();
        failed.allowConnect.countDown();
        failed.connectFailure = new IllegalStateException("authentication failed");
        BlockingProvider replacement = new BlockingProvider();
        replacement.allowConnect.countDown();
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, ConnectionIdentifier.of("default")))
                .thenReturn(failed, replacement);
        DataProviderRegistry registry = new DataProviderRegistry(factory, config, new RecordingLoggerAdapter());

        assertNull(registry.registerDatabase("plugin", "scope", DatabaseType.MYSQL, "default"));
        assertSame(replacement,
                registry.registerDatabase("plugin", "scope", DatabaseType.MYSQL, "default"));
        assertEquals(1, failed.connectCalls.get());
        assertEquals(1, failed.disconnectCalls.get());
        assertEquals(1, replacement.connectCalls.get());

        registry.shutdownAllDatabases();
        assertEquals(1, replacement.disconnectCalls.get());
    }

    @Test
    void concurrentFinalReleaseClosesProviderExactlyOnce() throws Exception {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler config = enabledConfig();
        BlockingProvider provider = new BlockingProvider();
        provider.allowConnect.countDown();
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, ConnectionIdentifier.of("default"))).thenReturn(provider);
        DataProviderRegistry registry = new DataProviderRegistry(factory, config, new RecordingLoggerAdapter());
        registry.registerDatabase("plugin", "scope", DatabaseType.MYSQL, "default");

        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<Void>> releases = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                releases.add(CompletableFuture.runAsync(() -> {
                    await(start);
                    registry.unregisterDatabase("plugin", "scope", DatabaseType.MYSQL, "default");
                }, executor));
            }
            start.countDown();
            CompletableFuture.allOf(releases.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, provider.disconnectCalls.get());
        assertTrue(registry.getActiveDatabases().isEmpty());
    }

    private static ConfigHandler enabledConfig() {
        ConfigHandler config = mock(ConfigHandler.class);
        when(config.isDatabaseTypeEnabled(DatabaseType.MYSQL)).thenReturn(true);
        return config;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static final class BlockingProvider implements ManagedDatabaseProvider {
        private final AtomicInteger connectCalls = new AtomicInteger();
        private final AtomicInteger disconnectCalls = new AtomicInteger();
        private final CountDownLatch connectStarted;
        private final CountDownLatch allowConnect;
        private volatile boolean connected;
        private volatile RuntimeException connectFailure;

        private BlockingProvider() {
            this(new CountDownLatch(0), new CountDownLatch(1));
        }

        private BlockingProvider(CountDownLatch connectStarted, CountDownLatch allowConnect) {
            this.connectStarted = connectStarted;
            this.allowConnect = allowConnect;
        }

        @Override
        public void connect() {
            connectCalls.incrementAndGet();
            connectStarted.countDown();
            await(allowConnect);
            if (connectFailure != null) {
                throw connectFailure;
            }
            connected = true;
        }

        @Override
        public void disconnect() {
            disconnectCalls.incrementAndGet();
            connected = false;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public boolean isLocallyConnected() {
            return connected;
        }

        @Override
        public DataAccess getDataAccess() {
            return null;
        }

        @Override
        public DataSource getDataSource() {
            return null;
        }
    }
}
