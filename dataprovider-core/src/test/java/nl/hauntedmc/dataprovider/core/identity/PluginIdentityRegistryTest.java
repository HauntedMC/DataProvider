package nl.hauntedmc.dataprovider.core.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginIdentityRegistryTest {

    @Test
    void invalidatedIdentityDoesNotBecomeValidWhenAPluginIdIsReused() {
        PluginIdentityRegistry registry = new PluginIdentityRegistry();
        ClassLoader firstLoader = new ClassLoader() { };
        PluginIdentity oldIdentity = registry.register("Example", firstLoader);

        registry.invalidate(firstLoader);
        PluginIdentity replacement = registry.register("example", new ClassLoader() { });

        assertFalse(registry.isActive(oldIdentity));
        assertTrue(registry.isActive(replacement));
        assertNotSame(oldIdentity, replacement);
    }

    @Test
    void identityCheckThroughputRemainsSuitableForOperationSubmission() {
        PluginIdentityRegistry registry = new PluginIdentityRegistry();
        PluginIdentity identity = registry.register("benchmark", getClass().getClassLoader());
        int operations = 1_000_000;
        long started = System.nanoTime();
        for (int i = 0; i < operations; i++) {
            assertTrue(registry.isActive(identity));
        }
        long elapsed = System.nanoTime() - started;

        // This deliberately generous floor catches accidental reintroduction of stack walking or scans.
        assertTrue(elapsed < java.util.concurrent.TimeUnit.SECONDS.toNanos(5),
                () -> "Identity enforcement became too slow: " + elapsed + " ns for " + operations + " checks");
    }
}
