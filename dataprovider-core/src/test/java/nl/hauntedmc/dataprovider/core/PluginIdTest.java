package nl.hauntedmc.dataprovider.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginIdTest {

    @Test
    void normalizesPluginIdentifiersCaseInsensitively() {
        assertEquals("dataprovideracceptance", PluginId.of(" DataProviderAcceptance ").value());
    }
}
