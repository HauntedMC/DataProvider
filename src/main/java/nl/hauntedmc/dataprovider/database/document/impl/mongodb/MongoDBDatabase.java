package nl.hauntedmc.dataprovider.database.document.impl.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MongoDBDatabase implements DocumentDatabaseProvider for MongoDB.
 * This version uses Configurate to load configuration values from a YAML file.
 */
public class MongoDBDatabase implements DocumentDatabaseProvider {

    private final CommentedConfigurationNode config;
    private MongoClient mongoClient;
    private ExecutorService executor;
    private MongoDBDataAccess dataAccess;
    private boolean connected;

    public MongoDBDatabase(CommentedConfigurationNode config) {
        this.config = config;
    }

    @Override
    public void connect() {
        if (connected && mongoClient != null) {
            DataProvider.getLogger().info("[MongoDBDatabase] Already connected; skipping re–initialization.");
            return;
        }
        try {
            final String host = config.node("host").getString("localhost");
            final int port = config.node("port").getInt(27017);
            final String databaseName = config.node("database").getString("minecraft");
            final String user = config.node("username").getString("");
            final String password = config.node("password").getString("");
            final String authSource = config.node("authSource").getString(databaseName);

            final String connectionString;
            if (!user.isEmpty() && !password.isEmpty()) {
                connectionString = String.format("mongodb://%s:%s@%s:%d/%s?authSource=%s",
                        encodeCredential(user), encodeCredential(password), host, port, databaseName, authSource);
            } else {
                connectionString = String.format("mongodb://%s:%d/%s", host, port, databaseName);
            }

            DataProvider.getLogger().info(String.format(
                    "[MongoDBDatabase] Connecting to Mongo at %s:%d (database=%s, auth=%s)",
                    host,
                    port,
                    databaseName,
                    (!user.isEmpty() && !password.isEmpty()) ? "enabled" : "disabled"
            ));

            ConnectionString connString = new ConnectionString(connectionString);
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(connString)
                    .retryWrites(true)
                    .build();

            mongoClient = MongoClients.create(settings);

            final int poolSize = Math.max(1, config.node("pool_size").getInt(8));
            executor = Executors.newFixedThreadPool(poolSize);

            dataAccess = new MongoDBDataAccess(mongoClient, databaseName, executor);

            connected = true;
            DataProvider.getLogger().info(String.format("[MongoDBDatabase] Connected successfully to Mongo at %s:%d", host, port));
        } catch (Exception e) {
            DataProvider.getLogger().error("[MongoDBDatabase] Connection failed.", e);
        }
    }

    @Override
    public void disconnect() {
        if (mongoClient != null) {
            mongoClient.close();
            DataProvider.getLogger().info("[MongoDBDatabase] MongoClient closed.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            DataProvider.getLogger().info("[MongoDBDatabase] ExecutorService shut down.");
        }
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected && mongoClient != null;
    }

    @Override
    public DocumentDataAccess getDataAccess() {
        return dataAccess;
    }

    private static String encodeCredential(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
