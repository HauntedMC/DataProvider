package nl.hauntedmc.dataprovider.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConnectionKeyTest {

    @Test
    void equalsRequiresMatchingPluginTypeAndIdentifier() {
        DatabaseConnectionKey a = new DatabaseConnectionKey("plugin", DatabaseType.MYSQL, "default");
        DatabaseConnectionKey b = new DatabaseConnectionKey("plugin", DatabaseType.MYSQL, "default");
        DatabaseConnectionKey differentPlugin = new DatabaseConnectionKey("other", DatabaseType.MYSQL, "default");
        DatabaseConnectionKey differentType = new DatabaseConnectionKey("plugin", DatabaseType.MONGODB, "default");
        DatabaseConnectionKey differentIdentifier = new DatabaseConnectionKey("plugin", DatabaseType.MYSQL, "secondary");

        assertEquals(a, b);
        assertNotEquals(a, differentPlugin);
        assertNotEquals(a, differentType);
        assertNotEquals(a, differentIdentifier);
        assertNotEquals(a, null);
    }

    @Test
    void toStringContainsAllFields() {
        DatabaseConnectionKey key = new DatabaseConnectionKey("plugin", DatabaseType.REDIS, "cache-main");
        String text = key.toString();

        assertTrue(text.contains("pluginName='plugin'"));
        assertTrue(text.contains("type=REDIS"));
        assertTrue(text.contains("connectionIdentifier='cache-main'"));
    }
}
