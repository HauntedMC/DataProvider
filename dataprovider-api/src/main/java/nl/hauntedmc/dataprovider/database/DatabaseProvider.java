package nl.hauntedmc.dataprovider.database;

import javax.sql.DataSource;

/**
 * Read-only database handle exposed to plugin consumers.
 * Lifecycle operations are owned by DataProvider internals.
 */
public interface DatabaseProvider {

    /**
     * Check if the database is currently connected.
     *
     * @return true if connected, false otherwise.
     */
    boolean isConnected();

    /**
     * Returns the data-access interface for this database.
     *
     * @return the data access object
     */
    DataAccess getDataAccess();

    /**
     * Reports whether this provider exposes a JDBC {@link DataSource}.
     *
     * @return true for providers that support {@link #getDataSource()}
     */
    default boolean supportsDataSource() {
        return false;
    }

    /**
     * Returns the JDBC {@link DataSource} when supported by this provider.
     *
     * @return the data source
     * @throws UnsupportedOperationException when {@link #supportsDataSource()} is false
     */
    DataSource getDataSource();

}
