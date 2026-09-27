package nl.hauntedmc.dataprovider.standalone;

import nl.hauntedmc.dataprovider.logging.LoggerAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StandaloneDataProviderTest {
    private static final LoggerAdapter SILENT = (level, message, failure) -> { };

    @Test void bindsOnlyItsOwnerAndRevokesOnShutdown() {
        StandaloneDataProvider host = StandaloneDataProvider.open("webapp",
                new StandaloneDataProvider.MysqlConnection("player_data_rw", "localhost", 3306, "test", "reader", "secret", "PREFERRED", 1),
                SILENT);
        assertNotNull(host.api());
        assertThrows(SecurityException.class, () -> host.api().forPlugin(new Object()));
        host.close();
        assertThrows(IllegalStateException.class, host::api);
    }

    @Test void rejectsInvalidConfigurationBeforeOpeningResources() {
        assertThrows(IllegalArgumentException.class, () -> new StandaloneDataProvider.MysqlConnection(
                "player_data_rw", "", 3306, "test", "reader", "secret", "PREFERRED", 1));
        assertThrows(IllegalArgumentException.class, () -> new StandaloneDataProvider.MysqlConnection(
                "player_data_rw", "localhost", 0, "test", "reader", "secret", "PREFERRED", 1));
    }

    @Test void canHostRedisWithoutCopyingPlayerDatabaseCredentials() {
        StandaloneDataProvider host = StandaloneDataProvider.open("webapp-events",
                new StandaloneDataProvider.RedisMessagingConnection("player_events", "localhost", 6379,
                        "default", "secret", 0, false), SILENT);
        assertNotNull(host.api());
        host.close();
    }
}
