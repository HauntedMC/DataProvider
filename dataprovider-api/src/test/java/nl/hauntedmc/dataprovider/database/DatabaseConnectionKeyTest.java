package nl.hauntedmc.dataprovider.database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void toStringDoesNotExposeIdentifiers() {
        DatabaseConnectionKey key = new DatabaseConnectionKey("plugin", DatabaseType.REDIS, "cache-main");
        String text = key.toString();

        assertTrue(text.contains("type=REDIS"));
        assertTrue(text.contains("identifiers=<redacted>"));
        assertFalse(text.contains("plugin"));
        assertFalse(text.contains("cache-main"));
    }

    @Test
    void rejectsInvalidComponents() {
        assertThrows(NullPointerException.class,
                () -> new DatabaseConnectionKey(null, DatabaseType.MYSQL, "default"));
        assertThrows(NullPointerException.class,
                () -> new DatabaseConnectionKey("plugin", null, "default"));
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseConnectionKey("plugin\nforged", DatabaseType.MYSQL, "default"));
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseConnectionKey("plugin", DatabaseType.MYSQL, "bad id"));
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseConnectionKey("x".repeat(129), DatabaseType.MYSQL, "default"));
    }
}
