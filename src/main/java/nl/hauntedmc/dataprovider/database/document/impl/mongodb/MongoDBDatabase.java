package nl.hauntedmc.dataprovider.database.document.impl.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import nl.hauntedmc.dataprovider.platform.common.logger.ILoggerAdapter;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.bson.Document;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * MongoDBDatabase implements DocumentDatabaseProvider for MongoDB.
 * This version uses Configurate to load configuration values from a YAML file.
 */
public class MongoDBDatabase implements DocumentDatabaseProvider {

    private final CommentedConfigurationNode config;
    private final ILoggerAdapter logger;
    private MongoClient mongoClient;
    private ExecutorService executor;
    private MongoDBDataAccess dataAccess;
    private boolean connected;
    private String databaseName;

    public MongoDBDatabase(CommentedConfigurationNode config, ILoggerAdapter logger) {
        this.config = config;
        this.logger = Objects.requireNonNull(logger, "Logger cannot be null.");
    }

    @Override
    public void connect() {
        if (connected && mongoClient != null) {
            logger.info("[MongoDBDatabase] Already connected; skipping re–initialization.");
            return;
        }
        MongoClient createdClient = null;
        ExecutorService createdExecutor = null;
        try {
            final String host = config.node("host").getString("localhost");
            final int port = config.node("port").getInt(27017);
            final String configuredDatabaseName = config.node("database").getString("minecraft");
            final String user = config.node("username").getString("");
            final String password = config.node("password").getString("");
            final String authSource = config.node("authSource").getString(configuredDatabaseName);

            final String connectionString;
            if (!user.isEmpty() && !password.isEmpty()) {
                connectionString = String.format("mongodb://%s:%s@%s:%d/%s?authSource=%s",
                        encodeCredential(user), encodeCredential(password), host, port, configuredDatabaseName, authSource);
            } else {
                connectionString = String.format("mongodb://%s:%d/%s", host, port, configuredDatabaseName);
            }

            logger.info(String.format(
                    "[MongoDBDatabase] Connecting to Mongo at %s:%d (database=%s, auth=%s)",
                    host,
                    port,
                    configuredDatabaseName,
                    (!user.isEmpty() && !password.isEmpty()) ? "enabled" : "disabled"
            ));

            ConnectionString connString = new ConnectionString(connectionString);
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(connString)
                    .retryWrites(true)
                    .build();

            createdClient = MongoClients.create(settings);
            createdClient.getDatabase(configuredDatabaseName).runCommand(new Document("ping", 1));

            final int poolSize = Math.max(1, config.node("pool_size").getInt(8));
            createdExecutor = Executors.newFixedThreadPool(poolSize);

            mongoClient = createdClient;
            executor = createdExecutor;
            databaseName = configuredDatabaseName;
            dataAccess = new MongoDBDataAccess(mongoClient, databaseName, executor);

            connected = true;
            logger.info(String.format("[MongoDBDatabase] Connected successfully to Mongo at %s:%d", host, port));
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
    public void disconnect() {
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
        if (!connected || mongoClient == null || databaseName == null || databaseName.isBlank()) {
            return false;
        }
        try {
            mongoClient.getDatabase(databaseName).runCommand(new Document("ping", 1));
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
}
