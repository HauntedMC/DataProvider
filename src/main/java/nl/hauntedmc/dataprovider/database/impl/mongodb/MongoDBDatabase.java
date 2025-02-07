package nl.hauntedmc.dataprovider.database.impl.mongodb;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.access.DataAccess;
import nl.hauntedmc.dataprovider.database.schema.SchemaManager;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MongoDBDatabase implements DatabaseProvider {

    private final FileConfiguration config;
    private final Logger logger;

    private MongoClient mongoClient;
    private ExecutorService executor;
    private DataAccess dataAccess;
    private SchemaManager schemaManager;

    private String databaseName;

    public MongoDBDatabase(FileConfiguration config) {
        this.config = config;
        this.logger = DataProvider.getInstance().getLogger();
    }

    @Override
    public void connect() {
        if (mongoClient != null) {
            logger.info("[MongoDBDatabase] Already connected, skipping re-initialization.");
            return;
        }

        try {
            // Read from config or environment variables
            String host = getEnvOrConfig("DB_MONGO_HOST", config.getString("host", "localhost"));
            int port = Integer.parseInt(getEnvOrConfig("DB_MONGO_PORT", String.valueOf(config.getInt("port", 27017))));
            this.databaseName = getEnvOrConfig("DB_MONGO_DATABASE", config.getString("database", "minecraft"));
            String user = getEnvOrConfig("DB_MONGO_USER", config.getString("username", ""));
            String password = getEnvOrConfig("DB_MONGO_PASS", config.getString("password", ""));
            String authSource = getEnvOrConfig("DB_MONGO_AUTHDB", config.getString("authSource", this.databaseName));

            // Construct the connection string
            // Example: mongodb://user:pass@host:27017/dbName?authSource=authSource
            // If username or password are empty, you might want a different approach
            String connectionString;
            if (!user.isEmpty() && !password.isEmpty()) {
                connectionString = String.format(
                        "mongodb://%s:%s@%s:%d/%s?authSource=%s",
                        user, password, host, port, databaseName, authSource
                );
            } else {
                // No authentication
                connectionString = String.format(
                        "mongodb://%s:%d/%s",
                        host, port, databaseName
                );
            }

            logger.info("[MongoDBDatabase] Connecting to " + connectionString);

            ConnectionString connString = new ConnectionString(connectionString);
            MongoClientSettings settings = MongoClientSettings.builder()
                    .applyConnectionString(connString)
                    .retryWrites(true)
                    .build();

            // Initialize the client
            mongoClient = MongoClients.create(settings);

            // ExecutorService for async DB operations
            int poolSize = config.getInt("pool_size", 10);
            executor = Executors.newFixedThreadPool(poolSize);

            // Initialize DataAccess and SchemaManager
            this.dataAccess = new MongoDBDataAccess(mongoClient, databaseName, executor);
            this.schemaManager = new MongoDBSchemaManager(mongoClient, databaseName, executor);

            logger.info("[MongoDBDatabase] Connected successfully to MongoDB at " + host + ":" + port);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[MongoDBDatabase] Connection failed!", e);
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
    }

    @Override
    public boolean isConnected() {
        // MongoDB Java driver does not have a direct "isValid" check,
        // so we check if mongoClient is null or already closed.
        // In many cases, the driver manages connections automatically behind the scenes.
        return mongoClient != null;
    }

    @Override
    public SchemaManager getSchemaManager() {
        if (schemaManager == null) {
            throw new IllegalStateException("[MongoDBDatabase] SchemaManager not initialized!");
        }
        return schemaManager;
    }

    @Override
    public DataAccess getDataAccess() {
        if (dataAccess == null) {
            throw new IllegalStateException("[MongoDBDatabase] DataAccess not initialized!");
        }
        return dataAccess;
    }

    /**
     * Utility: returns the environment variable if present, otherwise the fallback value.
     */
    private String getEnvOrConfig(String envKey, String fallback) {
        String envValue = System.getenv(envKey);
        return (envValue != null && !envValue.isEmpty()) ? envValue : fallback;
    }
}
