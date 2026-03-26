package nl.hauntedmc.dataprovider.internal;

import nl.hauntedmc.dataprovider.config.ConfigHandler;
import nl.hauntedmc.dataprovider.database.DataAccess;
import nl.hauntedmc.dataprovider.database.DatabaseConnectionKey;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.internal.ManagedDatabaseProvider;
import nl.hauntedmc.dataprovider.testutil.RecordingLoggerAdapter;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataProviderRegistryTest {

    @Test
    void constructorValidatesRequiredDependencies() {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler configHandler = mock(ConfigHandler.class);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();

        assertThrows(NullPointerException.class, () -> new DataProviderRegistry(null, configHandler, logger));
        assertThrows(NullPointerException.class, () -> new DataProviderRegistry(factory, null, logger));
        assertThrows(NullPointerException.class, () -> new DataProviderRegistry(factory, configHandler, null));
    }

    @Test
    void returnsNullWhenDatabaseTypeIsDisabled() {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler configHandler = mock(ConfigHandler.class);
        when(configHandler.isDatabaseTypeEnabled(DatabaseType.MYSQL)).thenReturn(false);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderRegistry registry = new DataProviderRegistry(factory, configHandler, logger);

        DatabaseProvider provider = registry.registerDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default");

        assertNull(provider);
        verify(configHandler).isDatabaseTypeEnabled(DatabaseType.MYSQL);
        assertTrue(logger.errorMessages().stream().anyMatch(m -> m.contains("disabled in the main config")));
    }

    @Test
    void registerReusesActiveConnectionAndReferenceCountingWorks() {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler configHandler = mock(ConfigHandler.class);
        when(configHandler.isDatabaseTypeEnabled(DatabaseType.MYSQL)).thenReturn(true);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderRegistry registry = new DataProviderRegistry(factory, configHandler, logger);

        RecordingProvider provider = new RecordingProvider(true);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, "default")).thenReturn(provider);

        DatabaseProvider first = registry.registerDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default");
        DatabaseProvider second = registry.registerDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default");

        assertSame(provider, first);
        assertSame(provider, second);
        assertEquals(1, provider.connectCalls);

        DatabaseConnectionKey key = new DatabaseConnectionKey("plugin", DatabaseType.MYSQL, "default");
        assertEquals(2, registry.getActiveDatabaseReferenceCounts().get(key));

        registry.unregisterDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default");
        assertEquals(0, provider.disconnectCalls);
        assertEquals(1, registry.getActiveDatabaseReferenceCounts().get(key));

        registry.unregisterDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default");
        assertEquals(1, provider.disconnectCalls);
        assertTrue(registry.getActiveDatabases().isEmpty());
    }

    @Test
    void unregisterByDifferentScopeDoesNotReleaseOtherFeatureReferences() {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler configHandler = mock(ConfigHandler.class);
        when(configHandler.isDatabaseTypeEnabled(DatabaseType.MYSQL)).thenReturn(true);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderRegistry registry = new DataProviderRegistry(factory, configHandler, logger);

        RecordingProvider provider = new RecordingProvider(true);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, "default")).thenReturn(provider);

        registry.registerDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default");
        registry.unregisterDatabase("plugin", "feature-b", DatabaseType.MYSQL, "default");

        DatabaseConnectionKey key = new DatabaseConnectionKey("plugin", DatabaseType.MYSQL, "default");
        assertEquals(1, registry.getActiveDatabaseReferenceCounts().get(key));
        assertEquals(0, provider.disconnectCalls);
        assertTrue(logger.warnMessages().stream().anyMatch(m -> m.contains("unregistered scope")));
    }

    @Test
    void unregisterAllReleasesOnlyCallerScopeReferencesWithinPlugin() {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler configHandler = mock(ConfigHandler.class);
        when(configHandler.isDatabaseTypeEnabled(DatabaseType.MYSQL)).thenReturn(true);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderRegistry registry = new DataProviderRegistry(factory, configHandler, logger);

        RecordingProvider provider = new RecordingProvider(true);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, "default")).thenReturn(provider);

        registry.registerDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default");
        registry.registerDatabase("plugin", "feature-b", DatabaseType.MYSQL, "default");

        registry.unregisterAllDatabases("plugin", "feature-a");

        DatabaseConnectionKey key = new DatabaseConnectionKey("plugin", DatabaseType.MYSQL, "default");
        assertEquals(1, registry.getActiveDatabaseReferenceCounts().get(key));
        assertEquals(0, provider.disconnectCalls);

        registry.unregisterAllDatabases("plugin", "feature-b");
        assertEquals(1, provider.disconnectCalls);
        assertTrue(registry.getActiveDatabases().isEmpty());
    }

    @Test
    void unregisterAllForPluginReleasesAllScopesWithinPlugin() {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler configHandler = mock(ConfigHandler.class);
        when(configHandler.isDatabaseTypeEnabled(DatabaseType.MYSQL)).thenReturn(true);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderRegistry registry = new DataProviderRegistry(factory, configHandler, logger);

        RecordingProvider provider = new RecordingProvider(true);
        RecordingProvider otherPluginProvider = new RecordingProvider(true);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, "default")).thenReturn(provider);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, "analytics")).thenReturn(otherPluginProvider);

        registry.registerDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default");
        registry.registerDatabase("plugin", "feature-b", DatabaseType.MYSQL, "default");
        registry.registerDatabase("other-plugin", "feature-x", DatabaseType.MYSQL, "analytics");

        registry.unregisterAllDatabasesForPlugin("plugin");

        assertEquals(1, provider.disconnectCalls);
        assertEquals(0, otherPluginProvider.disconnectCalls);
        assertEquals(1, registry.getActiveDatabases().size());
        assertTrue(registry.getActiveDatabases().containsKey(
                new DatabaseConnectionKey("other-plugin", DatabaseType.MYSQL, "analytics")
        ));
    }

    @Test
    void staleProviderIsRemovedDuringLookup() {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler configHandler = mock(ConfigHandler.class);
        when(configHandler.isDatabaseTypeEnabled(DatabaseType.MYSQL)).thenReturn(true);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderRegistry registry = new DataProviderRegistry(factory, configHandler, logger);

        RecordingProvider provider = new RecordingProvider(true);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, "default")).thenReturn(provider);

        registry.registerDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default");
        provider.connected = false;

        DatabaseProvider lookedUp = registry.getDatabase("plugin", DatabaseType.MYSQL, "default");
        assertNull(lookedUp);
        assertEquals(1, provider.disconnectCalls);
        assertTrue(registry.getActiveDatabases().isEmpty());
    }

    @Test
    void providerHealthCheckExceptionsAreTreatedAsStaleConnections() {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler configHandler = mock(ConfigHandler.class);
        when(configHandler.isDatabaseTypeEnabled(DatabaseType.MYSQL)).thenReturn(true);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderRegistry registry = new DataProviderRegistry(factory, configHandler, logger);

        RecordingProvider provider = new RecordingProvider(true);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, "default")).thenReturn(provider);
        registry.registerDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default");

        provider.healthFailure = new RuntimeException("health check failed");
        assertNull(registry.getDatabase("plugin", DatabaseType.MYSQL, "default"));
        assertTrue(logger.warnMessages().stream().anyMatch(m -> m.contains("Provider health check failed")));
    }

    @Test
    void staleProviderIsReplacedOnRegister() {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler configHandler = mock(ConfigHandler.class);
        when(configHandler.isDatabaseTypeEnabled(DatabaseType.MYSQL)).thenReturn(true);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderRegistry registry = new DataProviderRegistry(factory, configHandler, logger);

        RecordingProvider stale = new RecordingProvider(true);
        RecordingProvider replacement = new RecordingProvider(true);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, "default"))
                .thenReturn(stale)
                .thenReturn(replacement);

        registry.registerDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default");
        stale.connected = false;
        DatabaseProvider result = registry.registerDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default");

        assertSame(replacement, result);
        assertEquals(1, stale.disconnectCalls);
        assertEquals(1, replacement.connectCalls);
    }

    @Test
    void unregisterAllDatabasesOnlyAffectsRequestedPlugin() {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler configHandler = mock(ConfigHandler.class);
        when(configHandler.isDatabaseTypeEnabled(DatabaseType.MYSQL)).thenReturn(true);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderRegistry registry = new DataProviderRegistry(factory, configHandler, logger);

        RecordingProvider a = new RecordingProvider(true);
        RecordingProvider b = new RecordingProvider(true);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, "a")).thenReturn(a);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, "b")).thenReturn(b);

        registry.registerDatabase("plugin-a", "feature-a", DatabaseType.MYSQL, "a");
        registry.registerDatabase("plugin-b", "feature-b", DatabaseType.MYSQL, "b");

        registry.unregisterAllDatabases("plugin-a", "feature-a");

        assertEquals(1, a.disconnectCalls);
        assertEquals(0, b.disconnectCalls);
        ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> active = registry.getActiveDatabases();
        assertEquals(1, active.size());
        assertTrue(active.containsKey(new DatabaseConnectionKey("plugin-b", DatabaseType.MYSQL, "b")));
    }

    @Test
    void shutdownDisconnectsAllAndClearsRegistry() {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler configHandler = mock(ConfigHandler.class);
        when(configHandler.isDatabaseTypeEnabled(DatabaseType.MYSQL)).thenReturn(true);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderRegistry registry = new DataProviderRegistry(factory, configHandler, logger);

        RecordingProvider a = new RecordingProvider(true);
        RecordingProvider b = new RecordingProvider(true);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, "a")).thenReturn(a);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, "b")).thenReturn(b);

        registry.registerDatabase("plugin-a", "feature-a", DatabaseType.MYSQL, "a");
        registry.registerDatabase("plugin-b", "feature-b", DatabaseType.MYSQL, "b");

        registry.shutdownAllDatabases();

        assertEquals(1, a.disconnectCalls);
        assertEquals(1, b.disconnectCalls);
        assertTrue(registry.isClosed());
        assertThrows(IllegalStateException.class, registry::getActiveDatabases);
        assertThrows(IllegalStateException.class, registry::getActiveDatabaseReferenceCounts);
        assertTrue(logger.infoMessages().stream().anyMatch(m -> m.contains("All database connections have been closed")));
    }

    @Test
    void shutdownIsIdempotentAndBlocksFurtherOperations() {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler configHandler = mock(ConfigHandler.class);
        when(configHandler.isDatabaseTypeEnabled(DatabaseType.MYSQL)).thenReturn(true);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderRegistry registry = new DataProviderRegistry(factory, configHandler, logger);

        RecordingProvider provider = new RecordingProvider(true);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, "default")).thenReturn(provider);
        registry.registerDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default");

        registry.shutdownAllDatabases();
        registry.shutdownAllDatabases();

        assertEquals(1, provider.disconnectCalls);
        assertTrue(registry.isClosed());

        IllegalStateException registerFailure = assertThrows(
                IllegalStateException.class,
                () -> registry.registerDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default")
        );
        assertTrue(registerFailure.getMessage().contains("shut down"));
        assertThrows(
                IllegalStateException.class,
                () -> registry.getDatabase("plugin", DatabaseType.MYSQL, "default")
        );
        assertThrows(
                IllegalStateException.class,
                () -> registry.unregisterDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default")
        );
        assertThrows(IllegalStateException.class, () -> registry.unregisterAllDatabases("plugin", "feature-a"));
        assertThrows(IllegalStateException.class, registry::getActiveDatabases);
        assertThrows(IllegalStateException.class, registry::getActiveDatabaseReferenceCounts);
    }

    @Test
    void registerReturnsNullAndCleansUpWhenConnectThrows() {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler configHandler = mock(ConfigHandler.class);
        when(configHandler.isDatabaseTypeEnabled(DatabaseType.MYSQL)).thenReturn(true);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderRegistry registry = new DataProviderRegistry(factory, configHandler, logger);

        RecordingProvider provider = new RecordingProvider(true);
        provider.connectFailure = new RuntimeException("connect failed");
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, "default")).thenReturn(provider);

        DatabaseProvider result = registry.registerDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default");

        assertNull(result);
        assertEquals(1, provider.connectCalls);
        assertEquals(1, provider.disconnectCalls);
        assertTrue(registry.getActiveDatabases().isEmpty());
    }

    @Test
    void registerReturnsNullWhenFactoryReturnsNullProvider() {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler configHandler = mock(ConfigHandler.class);
        when(configHandler.isDatabaseTypeEnabled(DatabaseType.MYSQL)).thenReturn(true);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderRegistry registry = new DataProviderRegistry(factory, configHandler, logger);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, "default")).thenReturn(null);

        DatabaseProvider result = registry.registerDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default");
        assertNull(result);
        assertTrue(registry.getActiveDatabases().isEmpty());
    }

    @Test
    void getActiveSnapshotsExposeCurrentRegistryState() {
        DatabaseFactory factory = mock(DatabaseFactory.class);
        ConfigHandler configHandler = mock(ConfigHandler.class);
        when(configHandler.isDatabaseTypeEnabled(DatabaseType.MYSQL)).thenReturn(true);
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DataProviderRegistry registry = new DataProviderRegistry(factory, configHandler, logger);

        RecordingProvider provider = new RecordingProvider(true);
        when(factory.createDatabaseProvider(DatabaseType.MYSQL, "default")).thenReturn(provider);
        registry.registerDatabase("plugin", "feature-a", DatabaseType.MYSQL, "default");

        ConcurrentMap<DatabaseConnectionKey, DatabaseProvider> active = registry.getActiveDatabases();
        Map<DatabaseConnectionKey, Integer> refs = registry.getActiveDatabaseReferenceCounts();

        DatabaseConnectionKey key = new DatabaseConnectionKey("plugin", DatabaseType.MYSQL, "default");
        assertSame(provider, active.get(key));
        assertEquals(1, refs.get(key));
    }

    private static final class RecordingProvider implements ManagedDatabaseProvider {
        private boolean connected;
        private int connectCalls;
        private int disconnectCalls;
        private RuntimeException connectFailure;
        private RuntimeException healthFailure;

        private RecordingProvider(boolean connected) {
            this.connected = connected;
        }

        @Override
        public void connect() {
            connectCalls++;
            if (connectFailure != null) {
                throw connectFailure;
            }
            connected = true;
        }

        @Override
        public void disconnect() {
            disconnectCalls++;
            connected = false;
        }

        @Override
        public boolean isConnected() {
            if (healthFailure != null) {
                throw healthFailure;
            }
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
