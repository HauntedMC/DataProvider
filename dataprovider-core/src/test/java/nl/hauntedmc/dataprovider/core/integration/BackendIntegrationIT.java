package nl.hauntedmc.dataprovider.core.integration;

import nl.hauntedmc.dataprovider.core.database.document.impl.mongodb.MongoDBDatabase;
import nl.hauntedmc.dataprovider.core.database.keyvalue.impl.redis.RedisDatabase;
import nl.hauntedmc.dataprovider.core.database.relational.impl.mysql.MySQLDatabase;
import nl.hauntedmc.dataprovider.core.testutil.RecordingLoggerAdapter;
import nl.hauntedmc.dataprovider.database.document.model.DocumentQuery;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdate;
import nl.hauntedmc.dataprovider.database.document.model.DocumentUpdateOptions;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class BackendIntegrationIT {

    private static final String REDIS_PASSWORD = "integration-secret";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("dataprovider")
            .withUsername("dataprovider")
            .withPassword("dataprovider-secret");

    @Container
    private static final MongoDBContainer MONGODB = new MongoDBContainer(DockerImageName.parse("mongo:7.0"));

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    @Test
    void mysqlSupportsConnectionCrudTransactionsAndCleanup() throws Exception {
        MySQLDatabase database = new MySQLDatabase(mysqlConfig(MYSQL.getUsername(), MYSQL.getPassword()), logger());
        try {
            database.connect();
            assertTrue(database.isConnected());
            assertTrue(database.probeRemoteHealth());

            var access = database.getDataAccess();
            access.executeUpdate("CREATE TABLE provider_it (id INT PRIMARY KEY, value VARCHAR(64))").join();
            access.executeUpdate("INSERT INTO provider_it (id, value) VALUES (?, ?)", 1, "created").join();
            assertEquals("created", access.queryForSingleValue(
                    "SELECT value FROM provider_it WHERE id = ?", 1).join());

            access.executeUpdate("UPDATE provider_it SET value = ? WHERE id = ?", "updated", 1).join();
            assertEquals("updated", access.queryForSingleValue(
                    "SELECT value FROM provider_it WHERE id = ?", 1).join());

            access.executeTransactionally(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO provider_it (id, value) VALUES (?, ?)")) {
                    statement.setInt(1, 2);
                    statement.setString(2, "committed");
                    statement.executeUpdate();
                }
                return null;
            }).join();
            assertEquals(1L, ((Number) access.queryForSingleValue(
                    "SELECT COUNT(*) FROM provider_it WHERE id = 2").join()).longValue());

            assertThrows(CompletionException.class, () -> access.executeTransactionally(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO provider_it (id, value) VALUES (?, ?)")) {
                    statement.setInt(1, 3);
                    statement.setString(2, "rolled-back");
                    statement.executeUpdate();
                }
                throw new IllegalStateException("force rollback");
            }).join());
            assertEquals(0L, ((Number) access.queryForSingleValue(
                    "SELECT COUNT(*) FROM provider_it WHERE id = 3").join()).longValue());

            access.executeUpdate("DELETE FROM provider_it WHERE id = ?", 1).join();
            assertEquals(0L, ((Number) access.queryForSingleValue(
                    "SELECT COUNT(*) FROM provider_it WHERE id = 1").join()).longValue());
        } finally {
            database.disconnect();
        }

        assertFalse(database.isConnected());
        assertNull(database.getDataSource());
        assertThrows(IllegalStateException.class, database::getDataAccess);
    }

    @Test
    void mongodbSupportsCrudNullValuesAndCleanup() throws Exception {
        MongoDBDatabase database = new MongoDBDatabase(mongoConfig("", ""), logger());
        try {
            database.connect();
            assertTrue(database.isConnected());
            assertTrue(database.probeRemoteHealth());

            var access = database.getDataAccess();
            Map<String, Object> document = new HashMap<>();
            document.put("key", "nullable");
            document.put("value", null);
            document.put("state", "created");
            access.insertOne("provider_it", document).join();

            Map<String, Object> inserted = access.findOne(
                    "provider_it", new DocumentQuery().eq("key", "nullable")).join();
            assertTrue(inserted.containsKey("value"));
            assertNull(inserted.get("value"));
            assertEquals("created", inserted.get("state"));

            access.updateOne(
                    "provider_it",
                    new DocumentQuery().eq("key", "nullable"),
                    new DocumentUpdate().set("state", "updated").set("value", null),
                    new DocumentUpdateOptions()
            ).join();
            Map<String, Object> updated = access.findOne(
                    "provider_it", new DocumentQuery().eq("key", "nullable")).join();
            assertEquals("updated", updated.get("state"));
            assertTrue(updated.containsKey("value"));
            assertNull(updated.get("value"));

            access.deleteOne("provider_it", new DocumentQuery().eq("key", "nullable")).join();
            assertNull(access.findOne("provider_it", new DocumentQuery().eq("key", "nullable")).join());
        } finally {
            database.disconnect();
        }

        assertFalse(database.isConnected());
        assertNull(database.getDataAccess());
        assertFalse(database.probeRemoteHealth());
    }

    @Test
    void redisSupportsCrudExpiryAndCleanup() throws Exception {
        RedisDatabase database = new RedisDatabase(redisConfig(REDIS_PASSWORD), logger());
        try {
            database.connect();
            assertTrue(database.isConnected());
            assertTrue(database.probeRemoteHealth());

            var access = database.getDataAccess();
            access.setKey("provider:crud", "created").join();
            assertEquals("created", access.getKey("provider:crud").join());
            access.setKey("provider:crud", "updated").join();
            assertEquals("updated", access.getKey("provider:crud").join());
            access.deleteKey("provider:crud").join();
            assertNull(access.getKey("provider:crud").join());

            access.setKeyWithExpiry("provider:expiry", "temporary", 1).join();
            assertEquals("temporary", access.getKey("provider:expiry").join());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (access.getKey("provider:expiry").join() != null && System.nanoTime() < deadline) {
                Thread.sleep(100L);
            }
            assertNull(access.getKey("provider:expiry").join());
        } finally {
            database.disconnect();
        }

        assertFalse(database.isConnected());
        assertNull(database.getDataAccess());
        assertFalse(database.probeRemoteHealth());
    }

    @Test
    void invalidCredentialsAreRejectedByEveryBackend() throws Exception {
        MySQLDatabase mysql = new MySQLDatabase(mysqlConfig(MYSQL.getUsername(), "wrong-password"), logger());
        mysql.connect();
        assertFalse(mysql.isConnected());
        assertNull(mysql.getDataSource());
        assertThrows(IllegalStateException.class, mysql::getDataAccess);
        mysql.disconnect();

        MongoDBDatabase mongo = new MongoDBDatabase(mongoConfig("missing-user", "wrong-password"), logger());
        mongo.connect();
        assertFalse(mongo.isConnected());
        assertNull(mongo.getDataAccess());
        mongo.disconnect();

        RedisDatabase redis = new RedisDatabase(redisConfig("wrong-password"), logger());
        redis.connect();
        assertFalse(redis.isConnected());
        assertNull(redis.getDataAccess());
        redis.disconnect();
    }

    private static CommentedConfigurationNode mysqlConfig(String username, String password) throws Exception {
        CommentedConfigurationNode config = CommentedConfigurationNode.root();
        config.node("host").set(MYSQL.getHost());
        config.node("port").set(MYSQL.getMappedPort(3306));
        config.node("database").set(MYSQL.getDatabaseName());
        config.node("username").set(username);
        config.node("password").set(password);
        config.node("ssl_mode").set("DISABLED");
        config.node("allow_public_key_retrieval").set(true);
        config.node("pool_size").set(2);
        config.node("min_idle").set(0);
        config.node("connection_timeout_ms").set(2_000L);
        config.node("connect_timeout_ms").set(2_000);
        config.node("socket_timeout_ms").set(2_000);
        return config;
    }

    private static CommentedConfigurationNode mongoConfig(String username, String password) throws Exception {
        CommentedConfigurationNode config = CommentedConfigurationNode.root();
        config.node("host").set(MONGODB.getHost());
        config.node("port").set(MONGODB.getMappedPort(27017));
        config.node("database").set("dataprovider");
        config.node("username").set(username);
        config.node("password").set(password);
        config.node("authSource").set("admin");
        config.node("pool_size").set(2);
        config.node("connect_timeout_ms").set(2_000L);
        config.node("socket_timeout_ms").set(2_000L);
        config.node("server_selection_timeout_ms").set(2_000L);
        return config;
    }

    private static CommentedConfigurationNode redisConfig(String password) throws Exception {
        CommentedConfigurationNode config = CommentedConfigurationNode.root();
        config.node("host").set(REDIS.getHost());
        config.node("port").set(REDIS.getMappedPort(6379));
        config.node("password").set(password);
        config.node("database").set(0);
        config.node("pool", "connections").set(2);
        config.node("pool", "threads").set(2);
        config.node("pool", "min_idle").set(0);
        config.node("connection_timeout_ms").set(2_000);
        config.node("socket_timeout_ms").set(2_000);
        return config;
    }

    private static RecordingLoggerAdapter logger() {
        return new RecordingLoggerAdapter();
    }
}
