package nl.hauntedmc.dataprovider.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginIdTest {

    @Test
    void normalizesPluginIdentifiersCaseInsensitively() {
        assertEquals("dataprovideracceptance", PluginId.of(" DataProviderAcceptance ").value());
    }

    @Test
    void rejectsUnsafeOrOversizedPluginIdentifiers() {
        assertThrows(IllegalArgumentException.class, () -> PluginId.of("plugin\nforged"));
        assertThrows(IllegalArgumentException.class, () -> PluginId.of("plugin name"));
        assertThrows(IllegalArgumentException.class, () -> PluginId.of("x".repeat(129)));
    }
}
