package nl.hauntedmc.dataprovider.exception;

import nl.hauntedmc.dataprovider.database.DatabaseType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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

    /** Whether this context contains any safe diagnostic metadata. */
    public boolean hasDiagnostics() {
        return !diagnostics.isEmpty();
    }

    /** Returns a new context with one diagnostic entry added or replaced. */
    public DataProviderFailureContext withDiagnostic(String key, String value) {
        Objects.requireNonNull(key, "Diagnostic key cannot be null.");
        Objects.requireNonNull(value, "Diagnostic value cannot be null.");
        Map<String, String> updated = new LinkedHashMap<>(diagnostics);
        updated.put(key, value);
        return withDiagnostics(updated);
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
