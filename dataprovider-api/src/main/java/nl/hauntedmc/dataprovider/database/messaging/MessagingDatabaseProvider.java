package nl.hauntedmc.dataprovider.database.messaging;

import nl.hauntedmc.dataprovider.database.DatabaseProvider;
import nl.hauntedmc.dataprovider.database.messaging.durable.DurableMessagingDataAccess;

import javax.sql.DataSource;

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

    /**
     * Durable Streams-backed access for authoritative events. Pub/Sub remains available from
     * {@link #getDataAccess()} for disposable notifications.
     */
    default DurableMessagingDataAccess getDurableDataAccess() {
        throw new UnsupportedOperationException("This messaging provider does not support durable messaging");
    }

    @Override
    default DataSource getDataSource() {
        throw new UnsupportedOperationException("Messaging databases do not provide a DataSource");
    }
}
