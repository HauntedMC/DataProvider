package nl.hauntedmc.dataprovider.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseTypeTest {

    @Test
    void exposesExpectedConfigFileNames() {
        assertEquals("mysql.yml", DatabaseType.MYSQL.getConfigFileName());
        assertEquals("mongodb.yml", DatabaseType.MONGODB.getConfigFileName());
        assertEquals("redis.yml", DatabaseType.REDIS.getConfigFileName());
        assertEquals("redis_messaging.yml", DatabaseType.REDIS_MESSAGING.getConfigFileName());
    }
}
