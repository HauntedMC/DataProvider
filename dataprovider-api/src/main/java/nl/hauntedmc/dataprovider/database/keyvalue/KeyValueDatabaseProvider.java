package nl.hauntedmc.dataprovider.database.keyvalue;

import nl.hauntedmc.dataprovider.database.DatabaseProvider;

import javax.sql.DataSource;

/**
 * KeyValueDatabaseProvider is the parent interface for key–value
 * databases like Redis.
 */
public interface KeyValueDatabaseProvider extends DatabaseProvider {

    @Override
    KeyValueDataAccess getDataAccess();

    @Override
    default DataSource getDataSource() {
        throw new UnsupportedOperationException("Key-Value databases do not provide a DataSource");
    }
}
