package nl.hauntedmc.dataprovider.database.keyvalue;

import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;

/**
 * KeyValueDatabaseProvider is the parent interface for key-value
 * databases like Redis, Memcached, etc.
 */
public interface KeyValueDatabaseProvider extends BaseDatabaseProvider {

    /**
     * Override getDataAccess() to return KeyValueDataAccess, so clients
     * can do provider.getDataAccess().setKey(...) without casting.
     */
    @Override
    KeyValueDataAccess getDataAccess();
}
