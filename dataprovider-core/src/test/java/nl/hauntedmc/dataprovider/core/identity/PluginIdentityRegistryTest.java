package nl.hauntedmc.dataprovider.core.identity;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void concurrentReplacementLeavesExactlyOneActiveGeneration() throws Exception {
        PluginIdentityRegistry registry = new PluginIdentityRegistry();
        ClassLoader loader = new ClassLoader() {
        };
        PluginIdentity initial = registry.register("replacement", loader);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return registry.register("replacement", loader);
            });
            var second = executor.submit(() -> {
                start.await();
                return registry.register("replacement", loader);
            });
            start.countDown();
            PluginIdentity firstIdentity = first.get();
            PluginIdentity secondIdentity = second.get();

            assertFalse(registry.isActive(initial));
            assertTrue(registry.isActive(firstIdentity) ^ registry.isActive(secondIdentity));
            assertSame(registry.find(loader), registry.isActive(firstIdentity) ? firstIdentity : secondIdentity);
        }
    }

    @Test
    void rejectsDifferentPluginsThatShareAClassLoader() {
        PluginIdentityRegistry registry = new PluginIdentityRegistry();
        ClassLoader sharedLoader = new ClassLoader() {
        };
        PluginIdentity first = registry.register("first", sharedLoader);

        assertThrows(IllegalStateException.class, () -> registry.register("second", sharedLoader));
        assertTrue(registry.isActive(first));
        assertSame(first, registry.find(sharedLoader));
    }
}
