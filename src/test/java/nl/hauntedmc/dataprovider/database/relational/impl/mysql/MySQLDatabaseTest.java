package nl.hauntedmc.dataprovider.database.relational.impl.mysql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import nl.hauntedmc.dataprovider.testutil.RecordingLoggerAdapter;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySQLDatabaseTest {

    @Test
    void constructorRejectsNullLogger() {
        assertThrows(NullPointerException.class, () -> new MySQLDatabase(CommentedConfigurationNode.root(), null));
    }

    @Test
    void connectFailsWhenSecureTransportRequiresStrictSslMode() throws Exception {
        CommentedConfigurationNode config = CommentedConfigurationNode.root();
        config.node("require_secure_transport").set(true);
        config.node("ssl_mode").set("PREFERRED");

        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        MySQLDatabase database = new MySQLDatabase(config, logger);
        database.connect();

        assertFalse(database.isConnected());
        assertNull(database.getDataSource());
        assertThrows(IllegalStateException.class, database::getDataAccess);
        assertThrows(IllegalStateException.class, database::getSchemaManager);
        assertTrue(logger.errorMessages().stream().anyMatch(message ->
                message.contains("[MySQLDatabase] Connection failed!")));
    }

    @Test
    void disconnectIsSafeWhenNeverConnected() {
        MySQLDatabase database = new MySQLDatabase(CommentedConfigurationNode.root(), new RecordingLoggerAdapter());
        database.disconnect();
        assertFalse(database.isConnected());
        assertNull(database.getDataSource());
    }

    @Test
    void connectConfiguresExplicitMySqlDriverClass() {
        CommentedConfigurationNode config = CommentedConfigurationNode.root();
        RecordingLoggerAdapter logger = new RecordingLoggerAdapter();
        AtomicReference<HikariConfig> capturedConfig = new AtomicReference<>();
        MySQLDatabase database = new MySQLDatabase(config, logger) {
            @Override
            HikariDataSource createDataSource(HikariConfig hikariConfig) {
                capturedConfig.set(hikariConfig);
                throw new RuntimeException("stop after hikari config capture");
            }
        };

        database.connect();

        assertEquals(MySQLDatabase.MYSQL_DRIVER_CLASS_NAME, capturedConfig.get().getDriverClassName());
        assertTrue(logger.errorMessages().stream().anyMatch(message ->
                message.contains("[MySQLDatabase] Connection failed!")));
    }
}
