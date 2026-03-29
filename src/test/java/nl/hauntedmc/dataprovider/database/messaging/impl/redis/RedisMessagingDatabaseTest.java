package nl.hauntedmc.dataprovider.database.messaging.impl.redis;

import nl.hauntedmc.dataprovider.testutil.RecordingLoggerAdapter;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RedisMessagingDatabaseTest {

    @Test
    void constructorRejectsNullLogger() {
        assertThrows(NullPointerException.class, () -> new RedisMessagingDatabase(CommentedConfigurationNode.root(), null));
    }

    @Test
    void connectRejectsMissingTlsWhenSecureTransportIsRequired() throws Exception {
        CommentedConfigurationNode config = CommentedConfigurationNode.root();
        config.node("require_secure_transport").set(true);
        config.node("tls", "enabled").set(false);

        RedisMessagingDatabase database = new RedisMessagingDatabase(config, new RecordingLoggerAdapter());
        assertThrows(IllegalStateException.class, database::connect);
    }

    @Test
    void disconnectAndStateChecksAreSafeWhenNeverConnected() {
        RedisMessagingDatabase database = new RedisMessagingDatabase(
                CommentedConfigurationNode.root(),
                new RecordingLoggerAdapter()
        );

        database.disconnect();
        assertFalse(database.isConnected());
        assertNull(database.getDataAccess());
    }

    @Test
    void connectRejectsInsecureTlsFlags() throws Exception {
        CommentedConfigurationNode config = CommentedConfigurationNode.root();
        config.node("tls", "enabled").set(true);
        config.node("tls", "trust_all_certificates").set(true);

        RedisMessagingDatabase database = new RedisMessagingDatabase(config, new RecordingLoggerAdapter());
        assertThrows(IllegalStateException.class, database::connect);
    }
}
