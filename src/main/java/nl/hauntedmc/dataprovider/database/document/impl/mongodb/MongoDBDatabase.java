package nl.hauntedmc.dataprovider.database.document.impl.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import nl.hauntedmc.dataprovider.logger.DPLogger;
import org.bukkit.configuration.ConfigurationSection;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MongoDBDatabase implements DocumentDatabaseProvider for MongoDB.
 */
public class MongoDBDatabase implements DocumentDatabaseProvider {

    private final ConfigurationSection config;
    private MongoClient mongoClient;
    private ExecutorService executor;
    private MongoDBDataAccess dataAccess;
    private boolean connected;

    public MongoDBDatabase(ConfigurationSection config) {
        this.config = config;
    }

    @Override
    public void connect() {
        if (connected && mongoClient != null) {
            DPLogger.info("[MongoDBDatabase] Already connected; skipping re–initialization.");
            return;
        }
        try {
            final String host = config.getString("host", "localhost");
            final int port = config.getInt("port", 27017);
            final String databaseName = config.getString("database", "minecraft");
            final String user = config.getString("username", "");
            final String password = config.getString("password", "");
            final String authSource = config.getString("authSource", databaseName);

            final String connectionString;
            if (!user.isEmpty() && !password.isEmpty()) {
                connectionString = String.format("mongodb://%s:%s@%s:%d/%s?authSource=%s",
                        user, password, host, port, databaseName, authSource);
            } else {
                connectionString = String.format("mongodb://%s:%d/%s", host, port, databaseName);
            }

            DPLogger.info("[MongoDBDatabase] Connecting with: " + connectionString);

            ConnectionString connString = new ConnectionString(connectionString);
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(connString)
                    .retryWrites(true)
                    .build();

            mongoClient = MongoClients.create(settings);

            final int poolSize = config.getInt("pool_size", 8);
            executor = Executors.newFixedThreadPool(poolSize);

            dataAccess = new MongoDBDataAccess(mongoClient, databaseName, executor);

            connected = true;
            DPLogger.info(String.format("[MongoDBDatabase] Connected successfully to Mongo at %s:%d", host, port));
        } catch (Exception e) {
            DPLogger.error("[MongoDBDatabase] Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void disconnect() {
        if (mongoClient != null) {
            mongoClient.close();
            DPLogger.info("[MongoDBDatabase] MongoClient closed.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            DPLogger.info("[MongoDBDatabase] ExecutorService shut down.");
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
}
