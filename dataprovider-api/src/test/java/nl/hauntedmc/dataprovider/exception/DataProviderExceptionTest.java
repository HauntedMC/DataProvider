package nl.hauntedmc.dataprovider.exception;

import nl.hauntedmc.dataprovider.database.DatabaseType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataProviderExceptionTest {

    @Test
    void contextIsImmutableAndMetadataIsStable() {
        Map<String, String> diagnostics = new HashMap<>();
        diagnostics.put("sqlState", "23000");
        DataProviderRegistrationException exception = new DataProviderRegistrationException(
                "Registration failed safely.",
                new DataProviderFailureContext(
                        DatabaseType.MYSQL,
                        "main",
                        "registerDatabase",
                        RetryAdvice.CONDITIONAL,
                        ExecutionOutcome.NOT_STARTED,
                        diagnostics,
                        null
                ),
                null
        );
        diagnostics.put("sqlState", "changed");

        assertEquals(DataProviderErrorCode.REGISTRATION_FAILED, exception.errorCode());
        assertEquals("23000", exception.diagnostics().get("sqlState"));
        assertThrows(UnsupportedOperationException.class,
                () -> exception.diagnostics().put("other", "value"));
        assertNotNull(exception.diagnosticId());
    }

    @Test
    void sensitiveDiagnosticKeysAreRejected() {
        DataProviderFailureContext context = context(Map.of("password", "must-not-appear"), null);

        assertThrows(IllegalArgumentException.class,
                () -> new DataProviderRegistrationException("Safe message.", context, null));
    }

    @Test
    void malformedDiagnosticIdentifiersAreRejected() {
        DataProviderFailureContext context = context(Map.of(), "invalid identifier with spaces");

        assertThrows(IllegalArgumentException.class,
                () -> new DataProviderRegistrationException("Safe message.", context, null));
    }

    private static DataProviderFailureContext context(Map<String, String> diagnostics, String diagnosticId) {
        return new DataProviderFailureContext(
                DatabaseType.REDIS,
                "cache",
                "redis.getKey",
                RetryAdvice.NEVER,
                ExecutionOutcome.NOT_STARTED,
                diagnostics,
                diagnosticId
        );
    }
}
