package nl.hauntedmc.dataprovider.database.document.impl.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import nl.hauntedmc.dataprovider.database.document.DocumentDataAccess;
import nl.hauntedmc.dataprovider.database.document.DocumentDatabaseProvider;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * MongoDBDatabase implements DocumentDatabaseProvider for MongoDB.
 */
public class MongoDBDatabase implements DocumentDatabaseProvider {

    private final FileConfiguration config;
    private final Logger logger;
    private MongoClient mongoClient;
    private ExecutorService executor;
    private MongoDBDataAccess dataAccess;
    private boolean connected;

    public MongoDBDatabase(FileConfiguration config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public MongoDBDatabase(FileConfiguration config) {
        this(config, Logger.getLogger("MongoDBDatabase"));
    }

    @Override
    public void connect() {
        if (connected && mongoClient != null) {
            logger.info("[MongoDBDatabase] Already connected; skipping re–initialization.");
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

            logger.info("[MongoDBDatabase] Connecting with: " + connectionString);

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
            logger.info(String.format("[MongoDBDatabase] Connected successfully to Mongo at %s:%d", host, port));
        } catch (Exception e) {
            logger.severe("[MongoDBDatabase] Connection failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void disconnect() {
        if (mongoClient != null) {
            mongoClient.close();
            logger.info("[MongoDBDatabase] MongoClient closed.");
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            logger.info("[MongoDBDatabase] ExecutorService shut down.");
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
