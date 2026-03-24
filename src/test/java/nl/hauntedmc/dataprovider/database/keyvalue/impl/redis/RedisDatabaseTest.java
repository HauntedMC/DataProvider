package nl.hauntedmc.dataprovider.database.keyvalue.impl.redis;

import nl.hauntedmc.dataprovider.testutil.RecordingLoggerAdapter;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisDatabaseTest {

    @Test
    void constructorRejectsNullLogger() {
        assertThrows(NullPointerException.class, () -> new RedisDatabase(CommentedConfigurationNode.root(), null));
    }

    @Test
    void connectFailsWhenSecureTransportRequiresTls() throws Exception {
        CommentedConfigurationNode config = CommentedConfigurationNode.root();
        config.node("require_secure_transport").set(true);
        config.node("tls", "enabled").set(false);

        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        RedisDatabase database = new RedisDatabase(config, logger);
        database.connect();

        assertFalse(database.isConnected());
        assertNull(database.getDataAccess());
        assertTrue(logger.errorMessages().stream().anyMatch(message ->
                message.contains("[RedisDatabase] Connection failed.")));
    }

    @Test
    void disconnectIsSafeWhenNeverConnected() {
        RedisDatabase database = new RedisDatabase(CommentedConfigurationNode.root(), new RecordingLoggerAdapter());
        database.disconnect();
        assertFalse(database.isConnected());
        assertNull(database.getDataAccess());
    }
}
