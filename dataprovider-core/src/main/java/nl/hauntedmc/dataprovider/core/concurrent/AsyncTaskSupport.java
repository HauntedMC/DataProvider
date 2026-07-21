package nl.hauntedmc.dataprovider.core.concurrent;

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

    interface RejectionAwareRunnable extends Runnable {
        void reject(RejectedExecutionException rejection);
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
        RejectionAwareRunnable task = new RejectionAwareRunnable() {
            @Override
            public void run() {
                if (future.isDone()) {
                    return;
                }
                try {
                    future.complete(supplier.get());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            }

            @Override
            public void reject(RejectedExecutionException rejection) {
                future.completeExceptionally(rejection);
            }
        };
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new RejectedExecutionException(
                    "Rejected async operation '" + operationName + "' because capacity is exhausted or shutting down.",
                    e
            ));
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }
}
