package nl.hauntedmc.dataprovider.internal.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsyncTaskSupportTest {

    @Test
    void supplyAsyncRunsOnExecutorAndReturnsValue() {
        Executor directExecutor = Runnable::run;

        CompletableFuture<Integer> result = AsyncTaskSupport.supplyAsync(
                directExecutor,
                "unit.supply",
                () -> 42
        );

        assertEquals(42, result.join());
    }

    @Test
    void runAsyncReturnsFailedFutureWhenExecutorRejects() {
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("full");
        };

        CompletableFuture<Void> future = AsyncTaskSupport.runAsync(
                rejectingExecutor,
                "unit.reject",
                () -> {
                }
        );

        CompletionException ex = assertThrows(CompletionException.class, future::join);
        assertTrue(ex.getCause() instanceof RejectedExecutionException);
        assertTrue(ex.getCause().getMessage().contains("unit.reject"));
    }

    @Test
    void runAsyncPropagatesTaskFailures() {
        Executor directExecutor = Runnable::run;

        CompletableFuture<Void> future = AsyncTaskSupport.runAsync(
                directExecutor,
                "unit.failure",
                () -> {
                    throw new IllegalStateException("boom");
                }
        );

        CompletionException ex = assertThrows(CompletionException.class, future::join);
        assertTrue(ex.getCause() instanceof IllegalStateException);
        assertEquals("boom", ex.getCause().getMessage());
    }
}
