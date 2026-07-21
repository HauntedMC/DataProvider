package nl.hauntedmc.dataprovider.core.concurrent;

import nl.hauntedmc.dataprovider.exception.BackendUnavailableException;
import nl.hauntedmc.dataprovider.exception.ExecutionOutcome;
import nl.hauntedmc.dataprovider.exception.QueueSaturatedException;
import nl.hauntedmc.dataprovider.exception.RetryAdvice;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AsyncTaskSupportTest {

    @Test
    void supplyAsyncRunsOnExecutorAndReturnsValue() {
        Executor directExecutor = Runnable::run;
        CompletableFuture<Integer> result = AsyncTaskSupport.supplyAsync(
                directExecutor, "unit.supply", () -> 42);
        assertEquals(42, result.join());
    }

    @Test
    void runAsyncReturnsStructuredFailureWhenExecutorRejects() {
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("full internal queue detail");
        };
        CompletableFuture<Void> future = AsyncTaskSupport.runAsync(
                rejectingExecutor, "unit.reject", () -> { });

        CompletionException completion = assertThrows(CompletionException.class, future::join);
        QueueSaturatedException rejection = assertInstanceOf(
                QueueSaturatedException.class, completion.getCause());
        assertEquals("unit.reject", rejection.operationName());
        assertEquals(RetryAdvice.SAFE, rejection.retryAdvice());
        assertEquals(ExecutionOutcome.NOT_STARTED, rejection.executionOutcome());
    }

    @Test
    void runAsyncRedactsAndStructuresTaskFailures() {
        Executor directExecutor = Runnable::run;
        CompletableFuture<Void> future = AsyncTaskSupport.runAsync(
                directExecutor,
                "unit.failure",
                () -> {
                    throw new IllegalStateException("password=boom");
                }
        );

        CompletionException completion = assertThrows(CompletionException.class, future::join);
        BackendUnavailableException failure = assertInstanceOf(
                BackendUnavailableException.class, completion.getCause());
        assertEquals("unit.failure", failure.operationName());
        assertEquals("java.lang.IllegalStateException", failure.diagnostics().get("causeType"));
    }
}
