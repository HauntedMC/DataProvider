package nl.hauntedmc.dataprovider.testutil;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes submitted work on the calling thread.
 */
public final class DirectExecutorService extends AbstractExecutorService {

    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    @Override
    public void shutdown() {
        shutdown.set(true);
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown.set(true);
        return List.of();
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    @Override
    public boolean isTerminated() {
        return shutdown.get();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return shutdown.get();
    }

    @Override
    public void execute(Runnable command) {
        if (shutdown.get()) {
            throw new IllegalStateException("Executor is shut down.");
        }
        command.run();
    }
}
