package nl.hauntedmc.dataprovider.database.messaging.impl.rabbitmq;

import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * RabbitMQMessagingDatabase implements MessagingDatabaseProvider using RabbitMQ.
 */
public class RabbitMQMessagingDatabase implements MessagingDatabaseProvider {
    public RabbitMQMessagingDatabase(CommentedConfigurationNode cfg) {}

    @Override public void connect() {
        throw new UnsupportedOperationException("RabbitMQ support not implemented");
    }
    @Override public void disconnect() {}
    @Override public boolean isConnected() { return false; }
    @Override
    public MessagingDataAccess getDataAccess() {
        return new RabbitMQMessagingDataAccess();
    }
}
