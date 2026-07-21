package nl.hauntedmc.dataprovider.core.exception;

import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.exception.DataProviderFailureContext;
import nl.hauntedmc.dataprovider.exception.DataSerializationException;
import nl.hauntedmc.dataprovider.exception.ExecutionOutcome;
import nl.hauntedmc.dataprovider.exception.ProviderClosedException;
import nl.hauntedmc.dataprovider.exception.RetryAdvice;

import java.util.Map;
import java.util.concurrent.Executor;

/** Constructors for structured failures that occur before async submission. */
public final class StructuredFailures {

    private StructuredFailures() {
    }

    public static DataSerializationException serialization(
            Throwable failure,
            Executor executor,
            String operationName
    ) {
        Context context = context(executor);
        return new DataSerializationException(
                "Data serialization or deserialization failed.",
                new DataProviderFailureContext(
                        context.backendType,
                        context.connectionIdentifier,
                        operationName,
                        RetryAdvice.NEVER,
                        ExecutionOutcome.NOT_STARTED,
                        Map.of("causeType", failure.getClass().getName()),
                        null
                ),
                redactedCause(failure)
        );
    }

    public static ProviderClosedException closed(Executor executor, String operationName) {
        Context context = context(executor);
        return new ProviderClosedException(
                "The DataProvider provider is closed.",
                new DataProviderFailureContext(
                        context.backendType,
                        context.connectionIdentifier,
                        operationName,
                        RetryAdvice.NEVER,
                        ExecutionOutcome.NOT_STARTED,
                        Map.of(),
                        null
                ),
                null
        );
    }

    private static Context context(Executor executor) {
        if (executor instanceof ExecutionHandle handle) {
            return new Context(handle.backendType(), handle.connectionIdentifier());
        }
        return new Context(null, null);
    }

    private static Throwable redactedCause(Throwable failure) {
        return new RedactedCause(failure.getClass().getName());
    }

    private record Context(DatabaseType backendType, String connectionIdentifier) {
    }

    private static final class RedactedCause extends RuntimeException {
        private RedactedCause(String type) {
            super("Backend failure type: " + type, null, false, false);
        }
    }
}
