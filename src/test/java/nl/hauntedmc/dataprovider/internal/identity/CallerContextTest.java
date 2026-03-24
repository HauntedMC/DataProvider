package nl.hauntedmc.dataprovider.internal.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CallerContextTest {

    @Test
    void validatesPluginIdAndClassLoader() {
        ClassLoader classLoader = getClass().getClassLoader();
        assertThrows(IllegalArgumentException.class, () -> new CallerContext(null, classLoader));
        assertThrows(IllegalArgumentException.class, () -> new CallerContext(" ", classLoader));
        assertThrows(NullPointerException.class, () -> new CallerContext("plugin", null));
    }

    @Test
    void storesValuesWhenValid() {
        ClassLoader classLoader = getClass().getClassLoader();
        CallerContext context = new CallerContext("plugin-id", classLoader);

        assertEquals("plugin-id", context.pluginId());
        assertEquals(classLoader, context.classLoader());
    }
}
