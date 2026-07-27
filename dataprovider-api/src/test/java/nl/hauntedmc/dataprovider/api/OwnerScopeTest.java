package nl.hauntedmc.dataprovider.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OwnerScopeTest {

    @Test
    void factoryTrimsInputAndPreservesSupportedCharactersAndCase() {
        OwnerScope scope = OwnerScope.of("  Plugin.Main:$-scope_1  ");

        assertEquals("Plugin.Main:$-scope_1", scope.value());
        assertEquals("Plugin.Main:$-scope_1", scope.toString());
    }

    @Test
    void valueSemanticsUseTheNormalizedScope() {
        assertEquals(OwnerScope.of("scope"), OwnerScope.of(" scope "));
        assertNotEquals(OwnerScope.of("scope"), OwnerScope.of("Scope"));
        assertEquals(OwnerScope.of("scope").hashCode(), OwnerScope.of(" scope ").hashCode());
    }

    @Test
    void acceptsTheMaximumSupportedLength() {
        String maximumLengthScope = "a".repeat(256);

        assertEquals(maximumLengthScope, OwnerScope.of(maximumLengthScope).value());
    }

    @Test
    void rejectsNullBlankAndOversizedScopes() {
        assertThrows(NullPointerException.class, () -> OwnerScope.of(null));
        assertThrows(IllegalArgumentException.class, () -> OwnerScope.of(""));
        assertThrows(IllegalArgumentException.class, () -> OwnerScope.of(" \t\n "));
        assertThrows(IllegalArgumentException.class, () -> OwnerScope.of("a".repeat(257)));
    }

    @Test
    void rejectsUnsupportedCharactersAndEmbeddedWhitespace() {
        assertThrows(IllegalArgumentException.class, () -> OwnerScope.of("owner/scope"));
        assertThrows(IllegalArgumentException.class, () -> OwnerScope.of("owner\\scope"));
        assertThrows(IllegalArgumentException.class, () -> OwnerScope.of("owner scope"));
        assertThrows(IllegalArgumentException.class, () -> OwnerScope.of("owner\0scope"));
    }
}
