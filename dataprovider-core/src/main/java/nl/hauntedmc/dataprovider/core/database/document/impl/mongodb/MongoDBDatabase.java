package nl.hauntedmc.dataprovider.core.database.document.impl.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import nl.hauntedmc.dataprovider.core.ManagedDatabaseProvider;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.database.security.TlsSupport;
import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.bson.Document;
import org.spongepowered.configurate.CommentedConfigurationNode;

import javax.net.ssl.SSLContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** MongoDB provider backed by the shared document execution lane. */
public class MongoDBDatabase implements DocumentDatabaseProvider, ManagedDatabaseProvider {

    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._:\\-\\[\\]]+");
    private static final Pattern DATABASE_PATTERN = Pattern.compile("[A-Za-z0-9_.\\-]+");

    private final CommentedConfigurationNode config;
    private final LoggerAdapter logger;
    private final ExecutionHandle execution;
    private volatile MongoClient mongoClient;
    private volatile MongoDBDataAccess dataAccess;
    private volatile boolean connected;
    private volatile String databaseName;
    private volatile Throwable lifecycleFailure;
    private volatile int connectionPoolSize;

    public MongoDBDatabase(CommentedConfigurationNode config, LoggerAdapter logger) {
        this(config, logger, ExecutionHandle.direct());
    }

    public MongoDBDatabase(CommentedConfigurationNode config, LoggerAdapter logger, ExecutionHandle execution) {
        this.config = Objects.requireNonNull(config, "Config cannot be null.");
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
        this.execution = Objects.requireNonNull(execution, "Execution handle cannot be null.");
    }

    @Override
    public synchronized void connect() {
        if (connected && mongoClient != null) {
            logger.info("[MongoDBDatabase] Already connected; skipping re-initialization.");
            return;
        }
        MongoClient createdClient = null;
        try {
            String host = requireHost(config.node("host").getString("localhost"));
            int port = requireInRange(config.node("port").getInt(27017), 1, 65_535, "port");
            String configuredDatabaseName = requireDatabaseName(
                    config.node("database").getString("minecraft"), "database");
            String user = config.node("username").getString("");
            String password = config.node("password").getString("");
            String authSource = requireDatabaseName(
                    config.node("authSource").getString(configuredDatabaseName), "authSource");
            boolean tlsEnabled = config.node("tls", "enabled").getBoolean(false);
            boolean allowInvalidHostnames = config.node("tls", "allow_invalid_hostnames").getBoolean(false);
            boolean trustAllCertificates = config.node("tls", "trust_all_certificates").getBoolean(false);
            String trustStorePath = config.node("tls", "trust_store_path").getString("");
            String trustStorePassword = config.node("tls", "trust_store_password").getString("");
            String trustStoreType = config.node("tls", "trust_store_type").getString("");
            boolean requireSecureTransport = config.node("require_secure_transport").getBoolean(false);
            int configuredWorkerPoolSize = requireInRange(config.node("pool_size").getInt(8), 1, 256, "pool_size");
            int clientMaxPoolSize = requireInRange(
                    config.node("max_connection_pool_size").getInt(Math.max(16, configuredWorkerPoolSize)),
                    1, 1_000, "max_connection_pool_size");
            int clientMinPoolSize = requireInRange(config.node("min_connection_pool_size").getInt(0),
                    0, clientMaxPoolSize, "min_connection_pool_size");
            long connectTimeoutMs = requireInRange(config.node("connect_timeout_ms").getLong(5_000L),
                    250L, 300_000L, "connect_timeout_ms");
            long socketTimeoutMs = requireInRange(config.node("socket_timeout_ms").getLong(5_000L),
                    250L, 300_000L, "socket_timeout_ms");
            long serverSelectionTimeoutMs = requireInRange(
                    config.node("server_selection_timeout_ms").getLong(5_000L),
                    250L, 300_000L, "server_selection_timeout_ms");

            if (requireSecureTransport && !tlsEnabled) {
                throw new IllegalStateException("MongoDB require_secure_transport=true but tls.enabled=false");
            }
            if (!tlsEnabled) {
                logger.warn("[MongoDBDatabase] MongoDB connection is running without TLS.");
            } else if (trustAllCertificates || allowInvalidHostnames) {
                throw new IllegalStateException(
                        "MongoDB TLS hostname and certificate verification cannot be disabled.");
            }
            if (user.isBlank() != password.isBlank()) {
                throw new IllegalStateException("MongoDB username/password must either both be set or both be empty.");
            }
            if (user.isBlank()) {
                logger.warn("[MongoDBDatabase] MongoDB credentials are not configured; using unauthenticated connection.");
            }

            String connectionString = !user.isEmpty() && !password.isEmpty()
                    ? String.format("mongodb://%s:%s@%s:%d/%s?authSource=%s",
                    encodeCredential(user), encodeCredential(password), host, port, configuredDatabaseName, authSource)
                    : String.format("mongodb://%s:%d/%s", host, port, configuredDatabaseName);

            MongoClientSettings.Builder settingsBuilder = MongoClientSettings.builder()
                    .applyConnectionString(new ConnectionString(connectionString))
                    .retryWrites(true)
                    .retryReads(true)
                    .applyToConnectionPoolSettings(pool -> pool.maxSize(clientMaxPoolSize).minSize(clientMinPoolSize))
                    .applyToSocketSettings(socket -> socket
                            .connectTimeout((int) connectTimeoutMs, TimeUnit.MILLISECONDS)
                            .readTimeout((int) socketTimeoutMs, TimeUnit.MILLISECONDS))
                    .applyToClusterSettings(cluster ->
                            cluster.serverSelectionTimeout(serverSelectionTimeoutMs, TimeUnit.MILLISECONDS));
            if (tlsEnabled) {
                SSLContext sslContext = TlsSupport.createSslContext(trustStorePath, trustStorePassword, trustStoreType);
                settingsBuilder.applyToSslSettings(ssl -> {
                    ssl.enabled(true);
                    ssl.invalidHostNameAllowed(false);
                    ssl.context(sslContext);
                });
            }

            createdClient = MongoClients.create(settingsBuilder.build());
            createdClient.getDatabase(configuredDatabaseName).runCommand(new Document("ping", 1));
            mongoClient = createdClient;
            databaseName = configuredDatabaseName;
            connectionPoolSize = clientMaxPoolSize;
            dataAccess = new MongoDBDataAccess(mongoClient, databaseName, execution);
            connected = true;
            lifecycleFailure = null;
            logger.info(String.format(
                    "[MongoDBDatabase] Connected successfully to Mongo at %s:%d (tls=%s, clientPool=%d)",
                    host, port, tlsEnabled ? "enabled" : "disabled", clientMaxPoolSize));
        } catch (Exception e) {
            lifecycleFailure = e;
            if (createdClient != null) {
                createdClient.close();
            }
            connected = false;
            dataAccess = null;
            databaseName = null;
            logger.error("[MongoDBDatabase] Connection failed.", e);
        }
    }

    @Override
    public synchronized void disconnect() {
        execution.close();
        if (mongoClient != null) {
            mongoClient.close();
            logger.info("[MongoDBDatabase] MongoClient closed.");
        }
        mongoClient = null;
        dataAccess = null;
        databaseName = null;
        connected = false;
    }

    @Override
    public boolean isConnected() {
        MongoClient clientSnapshot = mongoClient;
        String databaseSnapshot = databaseName;
        return connected && clientSnapshot != null && databaseSnapshot != null && !databaseSnapshot.isBlank();
    }

    @Override
    public Throwable lifecycleFailure() {
        return lifecycleFailure;
    }

    @Override
    public boolean probeRemoteHealth() {
        MongoClient clientSnapshot = mongoClient;
        String databaseSnapshot = databaseName;
        if (!connected || clientSnapshot == null || databaseSnapshot == null || databaseSnapshot.isBlank()) {
            return false;
        }
        try {
            clientSnapshot.getDatabase(databaseSnapshot).runCommand(new Document("ping", 1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public DocumentDataAccess getDataAccess() {
        return dataAccess;
    }

    public int executionCapacity() {
        if (!isConnected() || connectionPoolSize < 1) {
            throw new IllegalStateException("[MongoDBDatabase] Mongo client not initialized!");
        }
        return connectionPoolSize;
    }

    /** Creates a logical provider view without creating another Mongo client. */
    public DocumentDatabaseProvider scoped(ExecutionHandle scopedExecution) {
        MongoClient client = mongoClient;
        String database = databaseName;
        if (!connected || client == null || database == null) {
            throw new IllegalStateException("[MongoDBDatabase] Mongo client not initialized!");
        }
        DocumentDataAccess accessView = new MongoDBDataAccess(client, database, scopedExecution);
        return new DocumentDatabaseProvider() {
            @Override public boolean isConnected() { return MongoDBDatabase.this.isConnected() && !scopedExecution.isClosed(); }
            @Override public DocumentDataAccess getDataAccess() { return accessView; }
        };
    }

    private static String encodeCredential(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MongoDB config '" + fieldName + "' cannot be null or blank.");
        }
        return value.trim();
    }

    private static String requireHost(String host) {
        String normalized = requireNonBlank(host, "host");
        if (!HOST_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("MongoDB config 'host' contains unsupported characters: " + normalized);
        }
        return normalized;
    }

    private static String requireDatabaseName(String value, String fieldName) {
        String normalized = requireNonBlank(value, fieldName);
        if (!DATABASE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "MongoDB config '" + fieldName + "' contains unsupported characters: " + normalized);
        }
        return normalized;
    }

    private static int requireInRange(int value, int min, int max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("MongoDB config '" + fieldName + "' must be between " + min + " and " + max
                    + ", but got " + value + ".");
        }
        return value;
    }

    private static long requireInRange(long value, long min, long max, String fieldName) {
        if (value < min || value > max) {
            throw new IllegalArgumentException("MongoDB config '" + fieldName + "' must be between " + min + " and " + max
                    + ", but got " + value + ".");
        }
        return value;
    }
}
