package nl.hauntedmc.dataprovider.internal.identity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StackCallerClassLoaderResolverTest {

    @Test
    void validatesArguments() {
        assertThrows(NullPointerException.class, () -> StackCallerClassLoaderResolver.resolveExternalCaller(null));
        assertThrows(NullPointerException.class, () -> StackCallerClassLoaderResolver.resolveExternalCallerChain(null));
        assertThrows(NullPointerException.class, () -> StackCallerClassLoaderResolver.resolveNearestCallerOutsidePackage(null));
    }

    @Test
    void resolvesExternalCallerRelativeToProvidedOwnLoader() {
        ClassLoader resolved = StackCallerClassLoaderResolver.resolveExternalCaller(new ClassLoader() {
        });
        assertEquals(getClass().getClassLoader(), resolved);
    }

    @Test
    void resolvesExternalCallerChainAndNearestOutsidePackage() {
        List<ClassLoader> chain = StackCallerClassLoaderResolver.resolveExternalCallerChain(new ClassLoader() {
        });
        assertNotNull(chain);
        assertFalse(chain.isEmpty());

        ClassLoader nearest = StackCallerClassLoaderResolver.resolveNearestCallerOutsidePackage("no.matching.prefix");
        assertEquals(getClass().getClassLoader(), nearest);
    }
}
