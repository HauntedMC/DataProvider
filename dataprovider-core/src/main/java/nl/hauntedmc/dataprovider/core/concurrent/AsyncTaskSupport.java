package nl.hauntedmc.dataprovider.core.concurrent;

import nl.hauntedmc.dataprovider.core.exception.DataProviderExceptionMapper;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Shared helpers for queue-backed async execution with rejection-safe structured futures. */
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

        boolean failed();
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
            private volatile boolean failed;

            @Override
            public void run() {
                if (future.isDone()) {
                    return;
                }
                try {
                    future.complete(supplier.get());
                } catch (Throwable throwable) {
                    failed = true;
                    future.completeExceptionally(
                            DataProviderExceptionMapper.translate(throwable, executor, operationName)
                    );
                }
            }

            @Override
            public void reject(RejectedExecutionException rejection) {
                failed = true;
                future.completeExceptionally(
                        DataProviderExceptionMapper.translate(rejection, executor, operationName)
                );
            }

            @Override
            public boolean failed() {
                return failed;
            }
        };
        try {
            executor.execute(task);
        } catch (ExecutionRejectedException e) {
            future.completeExceptionally(DataProviderExceptionMapper.translate(e, executor, operationName));
        } catch (RejectedExecutionException e) {
            ExecutionRejectedException rejection = new ExecutionRejectedException(
                    ExecutionRejectedException.Reason.LANE_QUEUE_FULL,
                    "Rejected async operation '" + operationName + "'.",
                    e
            );
            future.completeExceptionally(DataProviderExceptionMapper.translate(rejection, executor, operationName));
        } catch (RuntimeException e) {
            future.completeExceptionally(DataProviderExceptionMapper.translate(e, executor, operationName));
        }
        return future;
    }
}
