package nl.hauntedmc.dataprovider.database.document.impl.mongodb;

import nl.hauntedmc.dataprovider.testutil.RecordingLoggerAdapter;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MongoDBDatabaseTest {

    @Test
    void constructorRejectsNullLogger() {
        assertThrows(NullPointerException.class, () -> new MongoDBDatabase(CommentedConfigurationNode.root(), null));
    }

    @Test
    void connectFailsWhenSecureTransportRequiresTls() throws Exception {
        CommentedConfigurationNode config = CommentedConfigurationNode.root();
        config.node("require_secure_transport").set(true);
        config.node("tls", "enabled").set(false);

        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        MongoDBDatabase database = new MongoDBDatabase(config, logger);
        database.connect();

        assertFalse(database.isConnected());
        assertNull(database.getDataAccess());
        assertTrue(logger.errorMessages().stream().anyMatch(message ->
                message.contains("[MongoDBDatabase] Connection failed.")));
    }

    @Test
    void disconnectIsSafeWhenNeverConnected() {
        MongoDBDatabase database = new MongoDBDatabase(CommentedConfigurationNode.root(), new RecordingLoggerAdapter());
        database.disconnect();
        assertFalse(database.isConnected());
        assertNull(database.getDataAccess());
    }
}
