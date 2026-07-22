package nl.hauntedmc.dataprovider.exception;

import nl.hauntedmc.dataprovider.database.DatabaseType;

import java.util.Map;

/** Safe context attached to a structured DataProvider failure. */
public record DataProviderFailureContext(
        DatabaseType backendType,
        String connectionIdentifier,
        String operationName,
        RetryAdvice retryAdvice,
        ExecutionOutcome executionOutcome,
        Map<String, String> diagnostics,
        String diagnosticId
) {
    public DataProviderFailureContext {
        retryAdvice = retryAdvice == null ? RetryAdvice.NEVER : retryAdvice;
        executionOutcome = executionOutcome == null ? ExecutionOutcome.UNKNOWN : executionOutcome;
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }

    public static DataProviderFailureContext of(
            DatabaseType backendType,
            String connectionIdentifier,
            String operationName,
            RetryAdvice retryAdvice,
            ExecutionOutcome executionOutcome
    ) {
        return new DataProviderFailureContext(
                backendType,
                connectionIdentifier,
                operationName,
                retryAdvice,
                executionOutcome,
                Map.of(),
                null
        );
    }

    public DataProviderFailureContext withDiagnostics(Map<String, String> diagnostics) {
        return new DataProviderFailureContext(
                backendType,
                connectionIdentifier,
                operationName,
                retryAdvice,
                executionOutcome,
                diagnostics,
                diagnosticId
        );
    }

    public DataProviderFailureContext withDiagnosticId(String diagnosticId) {
        return new DataProviderFailureContext(
                backendType,
                connectionIdentifier,
                operationName,
                retryAdvice,
                executionOutcome,
                diagnostics,
                diagnosticId
        );
    }
}
