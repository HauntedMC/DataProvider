package nl.hauntedmc.dataprovider.core.api;

import nl.hauntedmc.dataprovider.api.observation.DataProviderObservation;
import nl.hauntedmc.dataprovider.api.observation.DataProviderObserver;
import nl.hauntedmc.dataprovider.api.observation.DataProviderOperationContext;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Isolates optional observation callbacks from the data path. */
final class DataProviderObservations {

    private DataProviderObservations() {
    }

    static <T> T observe(
            DataProviderObserver observer,
            DataProviderOperationContext context,
            Supplier<T> operation
    ) {
        Objects.requireNonNull(operation, "Operation cannot be null.");
        DataProviderObservation observation = start(observer, context);
        try {
            T result = operation.get();
            succeeded(observation);
            return result;
        } catch (RuntimeException | Error failure) {
            failed(observation, failure);
            throw failure;
        }
    }

    static void observe(
            DataProviderObserver observer,
            DataProviderOperationContext context,
            Runnable operation
    ) {
        observe(observer, context, () -> {
            operation.run();
            return null;
        });
    }

    static Object observeInvocation(
            DataProviderObserver observer,
            DataProviderOperationContext context,
            ThrowingOperation operation
    ) throws Throwable {
        DataProviderObservation observation = start(observer, context);
        final Object result;
        try {
            result = operation.execute();
        } catch (Throwable failure) {
            failed(observation, failure);
            throw failure;
        }
        if (result instanceof CompletionStage<?> completionStage) {
            completionStage.whenComplete((ignored, failure) -> {
                if (failure == null) {
                    succeeded(observation);
                } else {
                    failed(observation, failure);
                }
            });
        } else {
            succeeded(observation);
        }
        return result;
    }

    private static DataProviderObservation start(
            DataProviderObserver observer,
            DataProviderOperationContext context
    ) {
        if (observer == null) {
            return DataProviderObservation.noop();
        }
        try {
            DataProviderObservation observation = observer.start(context);
            return observation == null ? DataProviderObservation.noop() : observation;
        } catch (RuntimeException ignored) {
            return DataProviderObservation.noop();
        }
    }

    private static void succeeded(DataProviderObservation observation) {
        try {
            observation.succeeded();
        } catch (RuntimeException ignored) {
            // Observability must never change a successful data operation into a failure.
        }
    }

    private static void failed(DataProviderObservation observation, Throwable failure) {
        try {
            observation.failed(failure);
        } catch (RuntimeException ignored) {
            // Preserve the original data-operation failure.
        }
    }

    @FunctionalInterface
    interface ThrowingOperation {
        Object execute() throws Throwable;
    }
}
