package nl.hauntedmc.dataprovider.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTypeTest {

    @Test
    void exposesExpectedConfigFileNamesAndStableConfigKeys() {
        assertEquals("mysql.yml", DatabaseType.MYSQL.getConfigFileName());
        assertEquals("mongodb.yml", DatabaseType.MONGODB.getConfigFileName());
        assertEquals("redis.yml", DatabaseType.REDIS.getConfigFileName());
        assertEquals("redis_messaging.yml", DatabaseType.REDIS_MESSAGING.getConfigFileName());
        assertEquals("redis_messaging", DatabaseType.REDIS_MESSAGING.configKey());
    }

    @Test
    void parsesUserFacingTypeNamesWithoutLocaleOrSeparatorFriction() {
        assertEquals(DatabaseType.MYSQL, DatabaseType.parse("mysql").orElseThrow());
        assertEquals(DatabaseType.REDIS_MESSAGING, DatabaseType.parse("REDIS-MESSAGING").orElseThrow());
        assertEquals(DatabaseType.REDIS_MESSAGING, DatabaseType.parse(" redis_messaging ").orElseThrow());
        assertTrue(DatabaseType.parse("unknown").isEmpty());
        assertTrue(DatabaseType.parse(" ").isEmpty());
        assertTrue(DatabaseType.parse(null).isEmpty());
    }
}
