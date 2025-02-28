package nl.hauntedmc.dataprovider.database.messaging;

import nl.hauntedmc.dataprovider.database.DatabaseProvider;

/**
 * The provider interface for event messaging systems.
 */
public interface MessagingDatabaseProvider extends DatabaseProvider {

    /**
     * Returns a MessagingDataAccess instance for event messaging.
     *
     * @return the messaging data access
     */
    @Override
    MessagingDataAccess getDataAccess();
}
