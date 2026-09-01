package nl.hauntedmc.dataprovider.database.keyvalue;

import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.coordination.CoordinationDataAccess;

import javax.sql.DataSource;

/**
 * KeyValueDatabaseProvider is the parent interface for key–value
 * databases like Redis.
 */
public interface KeyValueDatabaseProvider extends DatabaseProvider {

    @Override
    KeyValueDataAccess getDataAccess();

    /** Returns the atomic coordination surface for this Redis connection. */
    CoordinationDataAccess getCoordinationDataAccess();

    @Override
    default DataSource getDataSource() {
        throw new UnsupportedOperationException("Key-Value databases do not provide a DataSource");
    }
}
