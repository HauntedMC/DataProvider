package nl.hauntedmc.dataprovider.standalone;

import nl.hauntedmc.dataprovider.api.DataProviderAPI;
import nl.hauntedmc.dataprovider.core.DataProvider;
import nl.hauntedmc.dataprovider.core.api.DefaultDataProviderApi;
import nl.hauntedmc.dataprovider.core.identity.CallerContext;
import nl.hauntedmc.dataprovider.core.identity.CallerContextResolver;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentity;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityRegistry;
import nl.hauntedmc.dataprovider.core.identity.PluginIdentityState;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

/** Hosts the existing DataProvider core outside Paper and Velocity. */
public final class StandaloneDataProvider implements AutoCloseable {
    private final Path temporaryConfig;
    private final PluginIdentityRegistry identities = new PluginIdentityRegistry();
    private final DataProvider provider;
    private final DataProviderAPI api;
    private volatile boolean closed;

    private StandaloneDataProvider(Path temporaryConfig, String ownerId, MysqlConnection connection, LoggerAdapter logger) {
        this.temporaryConfig = temporaryConfig;
        Object owner = new Owner();
        ClassLoader loader = StandaloneDataProvider.class.getClassLoader();
        identities.register(ownerId, loader);
        CallerContextResolver resolver = new CallerContextResolver() {
            @Override public CallerContext resolveCaller() {
                throw new SecurityException("Standalone access requires a bound API facade.");
            }
            @Override public PluginIdentity issueIdentity(Object candidate) {
                if (candidate != owner) throw new SecurityException("Unknown standalone owner.");
                return identities.find(loader);
            }
            @Override public PluginIdentityState identityState(PluginIdentity identity) {
                return identities.stateOf(identity);
            }
            @Override public boolean isKnownPlugin(String pluginId) {
                return identities.isKnownPlugin(pluginId);
            }
        };
        writeConfiguration(temporaryConfig, ownerId, connection);
        provider = new DataProvider(logger, temporaryConfig, loader, resolver);
        api = new DefaultDataProviderApi(provider.getDataProviderHandler()).forPlugin(owner);
    }

    public static StandaloneDataProvider open(String ownerId, MysqlConnection connection, LoggerAdapter logger) {
        ownerId = Objects.requireNonNull(ownerId, "ownerId").trim().toLowerCase(Locale.ROOT);
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(logger, "logger");
        Path directory = null;
        try {
            directory = Files.createTempDirectory("haunted-dataprovider-");
            StandaloneDataProvider runtime = new StandaloneDataProvider(directory, ownerId, connection, logger);
            deleteConfiguration(directory);
            return runtime;
        } catch (IOException failure) {
            deleteConfiguration(directory);
            throw new IllegalStateException("Cannot create standalone DataProvider configuration.", failure);
        } catch (RuntimeException failure) {
            deleteConfiguration(directory);
            throw failure;
        }
    }

    public DataProviderAPI api() {
        if (closed) throw new IllegalStateException("Standalone DataProvider is closed.");
        return api;
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            provider.shutdownAllDatabases();
        } finally {
            identities.invalidateAll();
            deleteConfiguration(temporaryConfig);
        }
    }

    private static void deleteConfiguration(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException ignored) { /* Short-lived owner-only configuration. */ }
            });
        } catch (IOException ignored) { /* Same cleanup best effort. */ }
    }

    private static void writeConfiguration(Path directory, String ownerId, MysqlConnection connection) {
        try {
            Path databases = Files.createDirectories(directory.resolve("databases"));
            CommentedConfigurationNode general = CommentedConfigurationNode.root();
            general.node("orm", "schema_mode").set("validate");
            YamlConfigurationLoader.builder().path(directory.resolve("config.yml")).build().save(general);

            CommentedConfigurationNode mysql = CommentedConfigurationNode.root();
            var named = mysql.node(connection.connectionId());
            named.node("access", "owner_plugin").set(ownerId);
            named.node("host").set(connection.host());
            named.node("port").set(connection.port());
            named.node("database").set(connection.database());
            named.node("username").set(connection.username());
            named.node("password").set(connection.password());
            named.node("ssl_mode").set(connection.sslMode());
            named.node("pool_size").set(connection.poolSize());
            named.node("min_idle").set(0);
            named.node("connection_timeout_ms").set(3000);
            named.node("connect_timeout_ms").set(3000);
            named.node("socket_timeout_ms").set(3000);
            YamlConfigurationLoader.builder().path(databases.resolve("mysql.yml")).build().save(mysql);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot prepare standalone DataProvider configuration.", failure);
        }
    }

    public record MysqlConnection(String connectionId, String host, int port, String database, String username, String password,
                                  String sslMode, int poolSize) {
        public MysqlConnection {
            connectionId = require(connectionId, "connectionId");
            if (!connectionId.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("Invalid connectionId.");
            }
            host = require(host, "host");
            database = require(database, "database");
            username = require(username, "username");
            password = Objects.requireNonNull(password, "password");
            sslMode = require(sslMode, "sslMode");
            if (port < 1 || port > 65535 || poolSize < 1 || poolSize > 32) {
                throw new IllegalArgumentException("Invalid standalone MySQL port or pool size.");
            }
        }
        private static String require(String value, String name) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required.");
            return value;
        }
    }

    private static final class Owner { }
}
