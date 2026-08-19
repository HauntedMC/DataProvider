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
     * Reports whether this provider implements durable acknowledged messaging.
     *
     * <p>Providers that override {@link #getDurableDataAccess()} automatically advertise the
     * capability. Implementations with connection-state-sensitive accessors may override this
     * method directly.</p>
     */
    default boolean supportsDurableMessaging() {
        try {
            getDurableDataAccess();
            return true;
        } catch (UnsupportedOperationException unsupported) {
            return false;
        }
    }

    /**
     * Durable Streams-backed access for authoritative events. Pub/Sub remains available from
     * {@link #getDataAccess()} for disposable notifications.
     *
     * @throws UnsupportedOperationException when {@link #supportsDurableMessaging()} is false
     */
    default DurableMessagingDataAccess getDurableDataAccess() {
        throw new UnsupportedOperationException("This messaging provider does not support durable messaging");
    }

    @Override
    default DataSource getDataSource() {
        throw new UnsupportedOperationException("Messaging databases do not provide a DataSource");
    }
}
