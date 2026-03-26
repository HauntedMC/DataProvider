package nl.hauntedmc.dataprovider.internal.concurrent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Shared helpers for queue-backed async execution with rejection-safe futures.
 */
public final class AsyncTaskSupport {

    private AsyncTaskSupport() {
    }

    @FunctionalInterface
    public interface CheckedRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    public interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    public static CompletableFuture<Void> runAsync(Executor executor, String operationName, CheckedRunnable runnable) {
        Objects.requireNonNull(runnable, "Runnable cannot be null.");
        return supplyAsync(executor, operationName, () -> {
            runnable.run();
            return null;
        });
    }

    public static <T> CompletableFuture<T> supplyAsync(
            Executor executor,
            String operationName,
            CheckedSupplier<T> supplier
    ) {
        Objects.requireNonNull(executor, "Executor cannot be null.");
        if (operationName == null || operationName.isBlank()) {
            throw new IllegalArgumentException("Operation name cannot be null or blank.");
        }
        Objects.requireNonNull(supplier, "Supplier cannot be null.");

        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new RejectedExecutionException(
                    "Rejected async operation '" + operationName + "' because the worker queue is full or shutting down.",
                    e
            ));
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }
}
