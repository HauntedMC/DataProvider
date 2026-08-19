package nl.hauntedmc.dataprovider.exception;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataProviderFailureContextTest {

    @Test
    void nullOptionalMetadataUsesSafeDefaults() {
        DataProviderFailureContext context = new DataProviderFailureContext(
                DatabaseType.REDIS, "cache", "get", null, null, null, null);

        assertEquals(RetryAdvice.NEVER, context.retryAdvice());
        assertEquals(ExecutionOutcome.UNKNOWN, context.executionOutcome());
        assertEquals(Map.of(), context.diagnostics());
        assertFalse(context.hasDiagnostics());
        assertNull(context.diagnosticId());
    }

    @Test
    void factoryCreatesAnEmptyContextWithTheRequestedExecutionMetadata() {
        DataProviderFailureContext context = DataProviderFailureContext.of(
                DatabaseType.MYSQL,
                "primary",
                "query",
                RetryAdvice.SAFE,
                ExecutionOutcome.NOT_STARTED
        );

        assertEquals(DatabaseType.MYSQL, context.backendType());
        assertEquals("primary", context.connectionIdentifier());
        assertEquals("query", context.operationName());
        assertEquals(RetryAdvice.SAFE, context.retryAdvice());
        assertEquals(ExecutionOutcome.NOT_STARTED, context.executionOutcome());
        assertEquals(Map.of(), context.diagnostics());
        assertNull(context.diagnosticId());
    }

    @Test
    void constructorDefensivelyCopiesDiagnostics() {
        Map<String, String> diagnostics = new HashMap<>();
        diagnostics.put("sqlState", "23000");

        DataProviderFailureContext context = new DataProviderFailureContext(
                DatabaseType.MYSQL,
                "primary",
                "insert",
                RetryAdvice.CONDITIONAL,
                ExecutionOutcome.UNKNOWN,
                diagnostics,
                "failure-1"
        );
        diagnostics.put("sqlState", "changed");

        assertEquals("23000", context.diagnostics().get("sqlState"));
        assertTrue(context.hasDiagnostics());
        assertThrows(UnsupportedOperationException.class,
                () -> context.diagnostics().put("vendorCode", "1"));
    }

    @Test
    void withDiagnosticBuildsIncrementallyWithoutMutatingOriginalContext() {
        DataProviderFailureContext original = DataProviderFailureContext.of(
                DatabaseType.REDIS,
                "cache",
                "get",
                RetryAdvice.SAFE,
                ExecutionOutcome.NOT_STARTED
        );

        DataProviderFailureContext changed = original
                .withDiagnostic("cacheState", "miss")
                .withDiagnostic("attempt", "1");

        assertFalse(original.hasDiagnostics());
        assertEquals(Map.of("cacheState", "miss", "attempt", "1"), changed.diagnostics());
        assertTrue(changed.hasDiagnostics());
        assertThrows(NullPointerException.class, () -> original.withDiagnostic(null, "value"));
        assertThrows(NullPointerException.class, () -> original.withDiagnostic("key", null));
    }

    @Test
    void withDiagnosticsReturnsANewContextAndPreservesAllOtherFields() {
        DataProviderFailureContext original = DataProviderFailureContext.of(
                DatabaseType.MONGODB,
                "documents",
                "findOne",
                RetryAdvice.CONDITIONAL,
                ExecutionOutcome.UNKNOWN
        ).withDiagnosticId("diagnostic-1");

        DataProviderFailureContext changed = original.withDiagnostics(Map.of("collection", "players"));

        assertEquals(Map.of(), original.diagnostics());
        assertEquals(Map.of("collection", "players"), changed.diagnostics());
        assertEquals(original.backendType(), changed.backendType());
        assertEquals(original.connectionIdentifier(), changed.connectionIdentifier());
        assertEquals(original.operationName(), changed.operationName());
        assertEquals(original.retryAdvice(), changed.retryAdvice());
        assertEquals(original.executionOutcome(), changed.executionOutcome());
        assertEquals(original.diagnosticId(), changed.diagnosticId());
    }

    @Test
    void withDiagnosticIdReturnsANewContextAndPreservesDiagnostics() {
        DataProviderFailureContext original = DataProviderFailureContext.of(
                DatabaseType.REDIS_MESSAGING,
                "events",
                "publish",
                RetryAdvice.SAFE,
                ExecutionOutcome.NOT_STARTED
        ).withDiagnostics(Map.of("destination", "network"));

        DataProviderFailureContext changed = original.withDiagnosticId("diagnostic-2");

        assertNull(original.diagnosticId());
        assertEquals("diagnostic-2", changed.diagnosticId());
        assertEquals(original.diagnostics(), changed.diagnostics());
        assertEquals(original.backendType(), changed.backendType());
        assertEquals(original.connectionIdentifier(), changed.connectionIdentifier());
        assertEquals(original.operationName(), changed.operationName());
        assertEquals(original.retryAdvice(), changed.retryAdvice());
        assertEquals(original.executionOutcome(), changed.executionOutcome());
    }

    @Test
    void diagnosticsRejectNullKeysAndValuesRatherThanProducingLatentFailures() {
        Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, "value");
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("key", null);

        assertThrows(NullPointerException.class, () -> new DataProviderFailureContext(
                null, null, null, null, null, nullKey, null));
        assertThrows(NullPointerException.class, () -> new DataProviderFailureContext(
                null, null, null, null, null, nullValue, null));
    }
}
