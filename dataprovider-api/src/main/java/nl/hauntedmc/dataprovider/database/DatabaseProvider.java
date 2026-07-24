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
     * Returns a BaseDataAccess object for this database.
     *
     * @return the data access object
     */
    DataAccess getDataAccess();

    /**
     * Returns a BaseDataAccess object for this database.
     *
     * @return the data access object
     */
    DataSource getDataSource();

}
