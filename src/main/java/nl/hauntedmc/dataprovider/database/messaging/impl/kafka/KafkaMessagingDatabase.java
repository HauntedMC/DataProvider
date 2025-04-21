package nl.hauntedmc.dataprovider.database.messaging.impl.kafka;

import nl.hauntedmc.dataprovider.database.messaging.MessagingDataAccess;
import nl.hauntedmc.dataprovider.database.messaging.MessagingDatabaseProvider;
import org.spongepowered.configurate.CommentedConfigurationNode;

/**
 * KafkaMessagingDatabase implements MessagingDatabaseProvider using Kafka.
 * This version uses Configurate (CommentedConfigurationNode) to load configuration values.
 */
public class KafkaMessagingDatabase implements MessagingDatabaseProvider {
    public KafkaMessagingDatabase(CommentedConfigurationNode cfg) {}

    @Override public void connect() {
        throw new UnsupportedOperationException("Kafka support not implemented");
    }
    @Override public void disconnect() {}
    @Override public boolean isConnected() { return false; }
    @Override
    public MessagingDataAccess getDataAccess() {
        return new KafkaMessagingDataAccess();
    }
}
