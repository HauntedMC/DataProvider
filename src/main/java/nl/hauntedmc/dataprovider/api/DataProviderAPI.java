package nl.hauntedmc.dataprovider.api;

import nl.hauntedmc.dataprovider.DataProvider;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;

/**
 * A fluent API for creating and connecting a database connection.
 * This class encapsulates the necessary parameters and calls the
 * registry’s registerDatabase method to create and connect the provider.
 */
public class DataProviderAPI {

    private final String pluginName;
    private final DatabaseType databaseType;
    private final String identifier;
    private BaseDatabaseProvider provider;

    private DataProviderAPI(String pluginName, DatabaseType databaseType, String identifier) {
        this.pluginName = pluginName;
        this.databaseType = databaseType;
        this.identifier = identifier;
    }

    /**
     * Returns a new builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Connects the database by registering it with the DataProviderRegistry.
     *
     * @return the underlying BaseDatabaseProvider instance after connection
     */
    public BaseDatabaseProvider connect() {
        // Use your registry to register (or retrieve) the connection.
        this.provider = DataProvider.getInstance()
                .getRegistry()
                .registerDatabase(pluginName, databaseType, identifier);
        return provider;
    }

    // Optional getters for information
    public String getPluginName() {
        return pluginName;
    }

    public DatabaseType getDatabaseType() {
        return databaseType;
    }

    public String getIdentifier() {
        return identifier;
    }

    public BaseDatabaseProvider getProvider() {
        return provider;
    }

    /**
     * Builder for DatabaseConnection.
     */
    public static class Builder {
        private String pluginName;
        private DatabaseType databaseType;
        private String identifier;

        /**
         * Sets the plugin name.
         * @param pluginName the plugin’s name
         * @return the builder instance
         */
        public Builder plugin(String pluginName) {
            this.pluginName = pluginName;
            return this;
        }

        /**
         * Sets the database type.
         * @param databaseType the type of database (e.g. MYSQL, MONGODB, etc.)
         * @return the builder instance
         */
        public Builder databaseType(DatabaseType databaseType) {
            this.databaseType = databaseType;
            return this;
        }

        /**
         * Sets the connection identifier.
         * This identifier is used to retrieve the proper configuration.
         * @param identifier the connection identifier
         * @return the builder instance
         */
        public Builder identifier(String identifier) {
            this.identifier = identifier;
            return this;
        }

        /**
         * Builds the DatabaseConnection after validating required fields.
         * @return a new DatabaseConnection instance
         */
        public DataProviderAPI build() {
            if (pluginName == null || pluginName.trim().isEmpty()) {
                throw new IllegalArgumentException("Plugin name must not be null or empty");
            }
            if (databaseType == null) {
                throw new IllegalArgumentException("Database type must not be null");
            }
            if (identifier == null || identifier.trim().isEmpty()) {
                throw new IllegalArgumentException("Identifier must not be null or empty");
            }
            return new DataProviderAPI(pluginName, databaseType, identifier);
        }
    }
}
