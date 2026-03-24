package nl.hauntedmc.dataprovider.internal.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedExecutorFactoryTest {

    @Test
    void rejectsInvalidFactoryArguments() {
        assertThrows(IllegalArgumentException.class, () -> BoundedExecutorFactory.create(" ", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> BoundedExecutorFactory.create("pool", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> BoundedExecutorFactory.create("pool", 1, 0));
    }

    @Test
    void createdThreadsUsePrefixAndDaemonFlag() throws Exception {
        ExecutorService executor = BoundedExecutorFactory.create("unit-test-pool", 1, 10);
        try {
            AtomicReference<Thread> executingThread = new AtomicReference<>();
            CountDownLatch finished = new CountDownLatch(1);

            executor.execute(() -> {
                executingThread.set(Thread.currentThread());
                finished.countDown();
            });

            assertTrue(finished.await(1, TimeUnit.SECONDS));
            Thread thread = executingThread.get();
            assertTrue(thread.getName().startsWith("unit-test-pool-"));
            assertTrue(thread.isDaemon());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsTasksWhenQueueIsFull() throws Exception {
        ExecutorService executor = BoundedExecutorFactory.create("overflow", 1, 1);
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);

        try {
            executor.execute(() -> {
                blockerStarted.countDown();
                try {
                    releaseBlocker.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });

            assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

            executor.execute(() -> {
                // Occupies the queue while the first task is blocked.
            });

            assertThrows(
                    RejectedExecutionException.class,
                    () -> executor.execute(() -> {
                    })
            );
        } finally {
            releaseBlocker.countDown();
            executor.shutdownNow();
        }
    }
}
