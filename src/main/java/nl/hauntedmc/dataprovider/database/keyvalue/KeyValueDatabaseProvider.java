package nl.hauntedmc.dataprovider.database.keyvalue;

import nl.hauntedmc.dataprovider.database.DatabaseProvider;

/**
 * KeyValueDatabaseProvider is the parent interface for key–value
 * databases like Redis, Memcached, etc.
 */
public interface KeyValueDatabaseProvider extends DatabaseProvider {

    @Override
    KeyValueDataAccess getDataAccess();
}
