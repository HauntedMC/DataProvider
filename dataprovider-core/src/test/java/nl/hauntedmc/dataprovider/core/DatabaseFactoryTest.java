package nl.hauntedmc.dataprovider.core;

import nl.hauntedmc.dataprovider.core.database.document.impl.mongodb.MongoDBDatabase;
import nl.hauntedmc.dataprovider.core.database.keyvalue.impl.redis.RedisDatabase;
import nl.hauntedmc.dataprovider.core.database.messaging.impl.redis.RedisMessagingDatabase;
import nl.hauntedmc.dataprovider.core.database.relational.impl.mysql.MySQLDatabase;
import nl.hauntedmc.dataprovider.core.exception.DataProviderExceptionMapper;
import nl.hauntedmc.dataprovider.core.concurrent.DataProviderExecutionRuntime;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionRuntimeConfig;
import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.relational.RelationalDatabaseProvider;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class DatabaseFactoryTest {

    @Test
    void constructorValidatesArguments() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = mock(DatabaseConfigMap.class);

        assertThrows(NullPointerException.class, () -> new DatabaseFactory(null, logger));
        assertThrows(NullPointerException.class, () -> new DatabaseFactory(configMap, null));
    }

    @Test
    void retainsTypedFailureAndLogsWhenConfigurationIsMissing() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = mock(DatabaseConfigMap.class);
        when(configMap.getAuthorizedConfig(
                eq(DatabaseType.MYSQL),
                eq(ConnectionIdentifier.of("missing")),
                eq(PluginId.of("internal")),
                any()
        )).thenReturn(null);

        DatabaseFactory factory = new DatabaseFactory(configMap, logger);
        assertThrows(DataProviderExceptionMapper.MissingConfigurationFailure.class,
                () -> factory.createDatabaseProvider(DatabaseType.MYSQL, "missing"));
        assertTrue(logger.errorMessages().stream().anyMatch(m -> m.contains("Could not load configuration")));
    }

    @Test
    void createsProviderImplementationForEachDatabaseType() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = mock(DatabaseConfigMap.class);
        CommentedConfigurationNode node = CommentedConfigurationNode.root();

        for (DatabaseType type : DatabaseType.values()) {
            when(configMap.getAuthorizedConfig(
                    eq(type),
                    eq(ConnectionIdentifier.of("default")),
                    eq(PluginId.of("internal")),
                    any()
            )).thenReturn(authorized(node, type));
        }

        DatabaseFactory factory = new DatabaseFactory(configMap, logger);

        assertInstanceOf(MySQLDatabase.class, factory.createDatabaseProvider(DatabaseType.MYSQL, "default"));
        assertInstanceOf(MongoDBDatabase.class, factory.createDatabaseProvider(DatabaseType.MONGODB, "default"));
        assertInstanceOf(RedisDatabase.class, factory.createDatabaseProvider(DatabaseType.REDIS, "default"));
        assertInstanceOf(RedisMessagingDatabase.class,
                factory.createDatabaseProvider(DatabaseType.REDIS_MESSAGING, "default"));
    }

    @Test
    void connectionFingerprintExcludesPolicyButDetectsCredentialsEndpointsTlsAndPoolSettings() {
        CommentedConfigurationNode baseline = connection("db.internal", "first-secret", 8);
        CommentedConfigurationNode accessOnly = (CommentedConfigurationNode) baseline.copy();
        accessOnly.node("access", "shared_with").raw(List.of("another-plugin"));
        CommentedConfigurationNode passwordChanged = (CommentedConfigurationNode) baseline.copy();
        passwordChanged.node("password").raw("rotated-secret");
        CommentedConfigurationNode endpointChanged = (CommentedConfigurationNode) baseline.copy();
        endpointChanged.node("host").raw("new-db.internal");
        CommentedConfigurationNode tlsChanged = (CommentedConfigurationNode) baseline.copy();
        tlsChanged.node("tls", "enabled").raw(true);
        CommentedConfigurationNode poolChanged = (CommentedConfigurationNode) baseline.copy();
        poolChanged.node("pool_size").raw(16);

        String fingerprint = DatabaseFactory.connectionFingerprint(baseline);

        assertEquals(fingerprint, DatabaseFactory.connectionFingerprint(accessOnly));
        assertNotEquals(fingerprint, DatabaseFactory.connectionFingerprint(passwordChanged));
        assertNotEquals(fingerprint, DatabaseFactory.connectionFingerprint(endpointChanged));
        assertNotEquals(fingerprint, DatabaseFactory.connectionFingerprint(tlsChanged));
        assertNotEquals(fingerprint, DatabaseFactory.connectionFingerprint(poolChanged));
        assertTrue(!fingerprint.contains("first-secret"));
    }

    @Test
    void preparedReloadSwapsOnlyAfterAConnectedReplacementIsReady() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = mock(DatabaseConfigMap.class);
        DataProviderExecutionRuntime runtime = new DataProviderExecutionRuntime(
                ExecutionRuntimeConfig.from(CommentedConfigurationNode.root())
        );

        CommentedConfigurationNode initial = connection("old-db.internal", "old-secret", 8);
        when(configMap.getAuthorizedConfig(
                eq(DatabaseType.MYSQL), eq(ConnectionIdentifier.of("default")), eq(PluginId.of("internal")), any()
        )).thenReturn(authorized(initial, DatabaseType.MYSQL));
        ReloadingFactory factory = new ReloadingFactory(configMap, logger, runtime);
        ManagedDatabaseProvider lease = factory.createDatabaseProvider(DatabaseType.MYSQL, "default");
        lease.connect();

        CommentedConfigurationNode replacement = connection("new-db.internal", "rotated-secret", 16);
        DatabaseConfigMap.DatabaseConfigSnapshot snapshot = new DatabaseConfigMap.DatabaseConfigSnapshot(java.util.Map.of(
                DatabaseType.MYSQL, replacement,
                DatabaseType.MONGODB, CommentedConfigurationNode.root(),
                DatabaseType.REDIS, CommentedConfigurationNode.root(),
                DatabaseType.REDIS_MESSAGING, CommentedConfigurationNode.root()
        ));
        when(configMap.getConfig(snapshot, DatabaseType.MYSQL, ConnectionIdentifier.of("default")))
                .thenReturn(replacement);

        DatabaseFactory.PreparedConfigurationReload plan = factory.prepareConfigurationReload(snapshot);

        assertEquals(2, factory.physicals.size());
        FakeMySql oldPhysical = factory.physicals.getFirst();
        FakeMySql newPhysical = factory.physicals.getLast();
        assertEquals(1, oldPhysical.connectCalls);
        assertEquals(1, newPhysical.connectCalls);
        assertEquals(0, oldPhysical.disconnectCalls);

        plan.commit();

        assertEquals(1, oldPhysical.disconnectCalls);
        assertEquals(0, newPhysical.disconnectCalls);
        assertTrue(lease.probeRemoteHealth());
        lease.disconnect();
        assertEquals(1, newPhysical.disconnectCalls);
        runtime.close();
    }

    @Test
    void rejectedReplacementLeavesTheCurrentPhysicalGenerationRunning() {
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        DatabaseConfigMap configMap = mock(DatabaseConfigMap.class);
        DataProviderExecutionRuntime runtime = new DataProviderExecutionRuntime(
                ExecutionRuntimeConfig.from(CommentedConfigurationNode.root())
        );
        try {
            CommentedConfigurationNode initial = connection("old-db.internal", "old-secret", 8);
            when(configMap.getAuthorizedConfig(
                    eq(DatabaseType.MYSQL), eq(ConnectionIdentifier.of("default")), eq(PluginId.of("internal")), any()
            )).thenReturn(authorized(initial, DatabaseType.MYSQL));
            ReloadingFactory factory = new ReloadingFactory(configMap, logger, runtime);
            ManagedDatabaseProvider lease = factory.createDatabaseProvider(DatabaseType.MYSQL, "default");
            lease.connect();

            CommentedConfigurationNode replacement = connection("new-db.internal", "rotated-secret", 16);
            DatabaseConfigMap.DatabaseConfigSnapshot snapshot = new DatabaseConfigMap.DatabaseConfigSnapshot(java.util.Map.of(
                    DatabaseType.MYSQL, replacement,
                    DatabaseType.MONGODB, CommentedConfigurationNode.root(),
                    DatabaseType.REDIS, CommentedConfigurationNode.root(),
                    DatabaseType.REDIS_MESSAGING, CommentedConfigurationNode.root()
            ));
            when(configMap.getConfig(snapshot, DatabaseType.MYSQL, ConnectionIdentifier.of("default")))
                    .thenReturn(replacement);
            factory.failNextConnection = true;

            assertThrows(IllegalStateException.class, () -> factory.prepareConfigurationReload(snapshot));

            FakeMySql oldPhysical = factory.physicals.getFirst();
            FakeMySql rejectedPhysical = factory.physicals.getLast();
            assertTrue(lease.probeRemoteHealth());
            assertEquals(0, oldPhysical.disconnectCalls);
            assertEquals(1, rejectedPhysical.disconnectCalls);
            lease.disconnect();
        } finally {
            runtime.close();
        }
    }

    private static DatabaseConfigMap.AuthorizedConnection authorized(
            CommentedConfigurationNode node,
            DatabaseType type
    ) {
        node.node("access", "owner_plugin").raw("internal");
        node.node("access", "shared_with").raw(List.of());
        return new DatabaseConfigMap.AuthorizedConnection(
                node,
                ConnectionAccessPolicy.from(node, type.name() + "/default")
        );
    }

    private static CommentedConfigurationNode connection(String host, String password, int poolSize) {
        CommentedConfigurationNode node = CommentedConfigurationNode.root();
        node.node("access", "owner_plugin").raw("internal");
        node.node("access", "shared_with").raw(List.of());
        node.node("host").raw(host);
        node.node("password").raw(password);
        node.node("pool_size").raw(poolSize);
        node.node("tls", "enabled").raw(false);
        return node;
    }

    private static final class ReloadingFactory extends DatabaseFactory {
        private final RecordingLoggerAdapter logger;
        private final List<FakeMySql> physicals = new ArrayList<>();
        private boolean failNextConnection;

        private ReloadingFactory(
                DatabaseConfigMap configMap,
                RecordingLoggerAdapter logger,
                DataProviderExecutionRuntime runtime
        ) {
            super(configMap, logger, runtime);
            this.logger = logger;
        }

        @Override
        protected ManagedDatabaseProvider createPhysical(DatabaseType type, CommentedConfigurationNode config) {
            if (type != DatabaseType.MYSQL) {
                throw new AssertionError("Unexpected backend: " + type);
            }
            FakeMySql provider = new FakeMySql(config, logger);
            provider.failOnConnect = failNextConnection;
            failNextConnection = false;
            physicals.add(provider);
            return provider;
        }
    }

    private static final class FakeMySql extends MySQLDatabase {
        private boolean connected;
        private boolean failOnConnect;
        private int connectCalls;
        private int disconnectCalls;

        private FakeMySql(CommentedConfigurationNode config, RecordingLoggerAdapter logger) {
            super(config, logger);
        }

        @Override public void connect() { connected = !failOnConnect; connectCalls++; }
        @Override public void disconnect() { connected = false; disconnectCalls++; }
        @Override public boolean isConnected() { return connected; }
        @Override public boolean isLocallyConnected() { return connected; }
        @Override public boolean probeRemoteHealth() { return connected; }
        @Override public int executionCapacity() { return 1; }
        @Override public RelationalDatabaseProvider scoped(ExecutionHandle ignored) {
            return mock(RelationalDatabaseProvider.class, invocation -> {
                if (invocation.getMethod().getName().equals("isConnected")) {
                    return connected;
                }
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            });
        }
    }
}
