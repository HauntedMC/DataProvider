package nl.hauntedmc.dataprovider.database.messaging;

import nl.hauntedmc.dataprovider.database.base.BaseDatabaseProvider;

/**
 * The provider interface for event messaging systems.
 */
public interface MessagingDatabaseProvider extends BaseDatabaseProvider {

    /**
     * Returns a MessagingDataAccess instance for event messaging.
     *
     * @return the messaging data access
     */
    @Override
    MessagingDataAccess getDataAccess();
}
