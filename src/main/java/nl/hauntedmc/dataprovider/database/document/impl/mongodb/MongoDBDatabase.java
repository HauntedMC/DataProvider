package nl.hauntedmc.dataprovider.database.document.impl.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import nl.hauntedmc.dataprovider.internal.concurrent.BoundedExecutorFactory;
import nl.hauntedmc.dataprovider.internal.ManagedDatabaseProvider;
import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import nl.hauntedmc.dataprovider.database.security.TlsSupport;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.bson.Document;

import javax.net.ssl.SSLContext;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * MongoDBDatabase implements DocumentDatabaseProvider for MongoDB.
 * This version uses Configurate to load configuration values from a YAML file.
 */
public class MongoDBDatabase implements DocumentDatabaseProvider, ManagedDatabaseProvider {

    private static final Pattern HOST_PATTERN = Pattern.compile("[A-Za-z0-9._:\\-\\[\\]]+");
    private static final Pattern DATABASE_PATTERN = Pattern.compile("[A-Za-z0-9_.\\-]+");

    private final CommentedConfigurationNode config;
    private final ILoggerAdapter logger;
    private volatile MongoClient mongoClient;
    private volatile ExecutorService executor;
    private volatile MongoDBDataAccess dataAccess;
    private volatile boolean connected;
    private volatile String databaseName;

    public MongoDBDatabase(CommentedConfigurationNode config, ILoggerAdapter logger) {
        this.config = config;
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    @Override
    public synchronized void connect() {
        if (connected && mongoClient != null) {
            logger.info("[MongoDBDatabase] Already connected; skipping re–initialization.");
            return;
        }
        MongoClient createdClient = null;
        ExecutorService createdExecutor = null;
        try {
            final String host = requireHost(config.node("host").getString("localhost"));
            final int port = requireInRange(config.node("port").getInt(27017), 1, 65_535, "port");
            final String configuredDatabaseName = requireDatabaseName(
                    config.node("database").getString("minecraft"),
                    "database"
            );
            final String user = config.node("username").getString("");
            final String password = config.node("password").getString("");
            final String authSource = requireDatabaseName(
                    config.node("authSource").getString(configuredDatabaseName),
                    "authSource"
            );
            final boolean tlsEnabled = config.node("tls", "enabled").getBoolean(false);
            final boolean allowInvalidHostnames = config.node("tls", "allow_invalid_hostnames").getBoolean(false);
            final boolean trustAllCertificates = config.node("tls", "trust_all_certificates").getBoolean(false);
            final String trustStorePath = config.node("tls", "trust_store_path").getString("");
            final String trustStorePassword = config.node("tls", "trust_store_password").getString("");
            final String trustStoreType = config.node("tls", "trust_store_type").getString("");
            final boolean requireSecureTransport = config.node("require_secure_transport").getBoolean(false);
            final int workerPoolSize = requireInRange(config.node("pool_size").getInt(8), 1, 256, "pool_size");
            final int queueCapacity = requireInRange(
                    config.node("queue_capacity").getInt(workerPoolSize * 200),
                    workerPoolSize,
                    1_000_000,
                    "queue_capacity"
            );
            final int clientMaxPoolSize = requireInRange(
                    config.node("max_connection_pool_size").getInt(Math.max(16, workerPoolSize)),
                    1,
                    1_000,
                    "max_connection_pool_size"
            );
            final int clientMinPoolSize = requireInRange(
                    config.node("min_connection_pool_size").getInt(0),
                    0,
                    clientMaxPoolSize,
                    "min_connection_pool_size"
            );
            final long connectTimeoutMs = requireInRange(
                    config.node("connect_timeout_ms").getLong(5_000L),
                    250L,
                    300_000L,
                    "connect_timeout_ms"
            );
            final long socketTimeoutMs = requireInRange(
                    config.node("socket_timeout_ms").getLong(5_000L),
                    250L,
                    300_000L,
                    "socket_timeout_ms"
            );
            final long serverSelectionTimeoutMs = requireInRange(
                    config.node("server_selection_timeout_ms").getLong(5_000L),
                    250L,
                    300_000L,
                    "server_selection_timeout_ms"
            );

            if (requireSecureTransport && !tlsEnabled) {
                throw new IllegalStateException("MongoDB require_secure_transport=true but tls.enabled=false");
            }
            if (!tlsEnabled) {
                logger.warn("[MongoDBDatabase] MongoDB connection is running without TLS.");
            } else if (trustAllCertificates || allowInvalidHostnames) {
                throw new IllegalStateException(
                        "MongoDB tls.allow_invalid_hostnames must be false and tls.trust_all_certificates must be false in DataProvider 2.0."
                );
            }
            if (user.isBlank() != password.isBlank()) {
                throw new IllegalStateException("MongoDB username/password must either both be set or both be empty.");
            }
            if (user.isBlank()) {
                logger.warn("[MongoDBDatabase] MongoDB credentials are not configured; using unauthenticated connection.");
            }

            final String connectionString;
            if (!user.isEmpty() && !password.isEmpty()) {
                connectionString = String.format("mongodb://%s:%s@%s:%d/%s?authSource=%s",
                        encodeCredential(user), encodeCredential(password), host, port, configuredDatabaseName, authSource);
            } else {
                connectionString = String.format("mongodb://%s:%d/%s", host, port, configuredDatabaseName);
            }

            logger.info(String.format(
                    "[MongoDBDatabase] Connecting to Mongo at %s:%d (database=%s, auth=%s, tls=%s)",
                    host,
                    port,
                    configuredDatabaseName,
                    (!user.isEmpty() && !password.isEmpty()) ? "enabled" : "disabled",
                    tlsEnabled ? "enabled" : "disabled"
            ));

            ConnectionString connString = new ConnectionString(connectionString);
            MongoClientSettings.Builder settingsBuilder = MongoClientSettings.builder()
                    .applyConnectionString(connString)
                    .retryWrites(true)
                    .retryReads(true)
                    .applyToConnectionPoolSettings(pool -> pool
                            .maxSize(clientMaxPoolSize)
                            .minSize(clientMinPoolSize))
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

            MongoClientSettings settings = settingsBuilder.build();

            createdClient = MongoClients.create(settings);
            createdClient.getDatabase(configuredDatabaseName).runCommand(new Document("ping", 1));

            createdExecutor = BoundedExecutorFactory.create("dataprovider-mongodb", workerPoolSize, queueCapacity);

            mongoClient = createdClient;
            executor = createdExecutor;
            databaseName = configuredDatabaseName;
            dataAccess = new MongoDBDataAccess(mongoClient, databaseName, executor);

            connected = true;
            logger.info(String.format(
                    "[MongoDBDatabase] Connected successfully to Mongo at %s:%d (tls=%s, clientPool=%d, workerPool=%d, queueCapacity=%d)",
                    host,
                    port,
                    tlsEnabled ? "enabled" : "disabled",
                    clientMaxPoolSize,
                    workerPoolSize,
                    queueCapacity
            ));
        } catch (Exception e) {
            if (createdExecutor != null) {
                createdExecutor.shutdownNow();
            }
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
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            logger.info("[MongoDBDatabase] ExecutorService shut down.");
        }
        if (mongoClient != null) {
            mongoClient.close();
            logger.info("[MongoDBDatabase] MongoClient closed.");
        }
        executor = null;
        mongoClient = null;
        dataAccess = null;
        databaseName = null;
        connected = false;
    }

    @Override
    public boolean isConnected() {
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
                    "MongoDB config '" + fieldName + "' contains unsupported characters: " + normalized
            );
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
