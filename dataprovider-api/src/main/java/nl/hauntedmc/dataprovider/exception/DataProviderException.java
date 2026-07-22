package nl.hauntedmc.dataprovider.exception;

import nl.hauntedmc.dataprovider.database.DatabaseType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Base type for safe, structured failures exposed by DataProvider. */
public abstract class DataProviderException extends RuntimeException {

    private static final Pattern DIAGNOSTIC_KEY_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final Pattern DIAGNOSTIC_ID_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");

    private final DataProviderErrorCode errorCode;
    private final DatabaseType backendType;
    private final String connectionIdentifier;
    private final String operationName;
    private final RetryAdvice retryAdvice;
    private final ExecutionOutcome executionOutcome;
    private final Map<String, String> diagnostics;
    private final String diagnosticId;

    protected DataProviderException(
            DataProviderErrorCode errorCode,
            String safeMessage,
            DataProviderFailureContext context,
            Throwable safeCause
    ) {
        this(
                errorCode,
                safeMessage,
                Objects.requireNonNull(context, "Failure context cannot be null.").backendType(),
                context.connectionIdentifier(),
                context.operationName(),
                context.retryAdvice(),
                context.executionOutcome(),
                context.diagnostics(),
                context.diagnosticId(),
                safeCause
        );
    }

    protected DataProviderException(
            DataProviderErrorCode errorCode,
            String safeMessage,
            DatabaseType backendType,
            String connectionIdentifier,
            String operationName,
            RetryAdvice retryAdvice,
            ExecutionOutcome executionOutcome,
            Map<String, String> diagnostics,
            String diagnosticId,
            Throwable safeCause
    ) {
        super(requireSafeText(safeMessage, "safeMessage"), safeCause);
        this.errorCode = Objects.requireNonNull(errorCode, "Error code cannot be null.");
        this.backendType = backendType;
        this.connectionIdentifier = normalizeNullable(connectionIdentifier);
        this.operationName = normalizeNullable(operationName);
        this.retryAdvice = Objects.requireNonNull(retryAdvice, "Retry advice cannot be null.");
        this.executionOutcome = Objects.requireNonNull(executionOutcome, "Execution outcome cannot be null.");
        this.diagnostics = sanitizeDiagnostics(diagnostics);
        this.diagnosticId = normalizeDiagnosticId(diagnosticId);
    }

    public final DataProviderErrorCode errorCode() {
        return errorCode;
    }

    public final DatabaseType backendType() {
        return backendType;
    }

    public final String connectionIdentifier() {
        return connectionIdentifier;
    }

    public final String operationName() {
        return operationName;
    }

    public final RetryAdvice retryAdvice() {
        return retryAdvice;
    }

    public final boolean retryable() {
        return retryAdvice != RetryAdvice.NEVER;
    }

    public final ExecutionOutcome executionOutcome() {
        return executionOutcome;
    }

    public final Map<String, String> diagnostics() {
        return diagnostics;
    }

    public final String diagnosticId() {
        return diagnosticId;
    }

    private static Map<String, String> sanitizeDiagnostics(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> safe = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalizedKey = requireSafeText(key, "diagnostic key");
            if (!DIAGNOSTIC_KEY_PATTERN.matcher(normalizedKey).matches()) {
                throw new IllegalArgumentException("Unsupported diagnostic key: " + normalizedKey);
            }
            if (isSensitiveKey(normalizedKey)) {
                throw new IllegalArgumentException("Sensitive diagnostic keys are not allowed: " + normalizedKey);
            }
            String normalizedValue = requireSafeText(value, "diagnostic value");
            if (normalizedValue.length() > 256) {
                throw new IllegalArgumentException("Diagnostic values cannot exceed 256 characters.");
            }
            safe.put(normalizedKey, normalizedValue);
        });
        return Map.copyOf(safe);
    }

    private static boolean isSensitiveKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("secret") || lower.contains("token")
                || lower.contains("credential") || lower.contains("authorization") || lower.contains("payload")
                || lower.contains("query") || lower.contains("parameter") || lower.contains("url");
    }

    private static String normalizeDiagnosticId(String diagnosticId) {
        if (diagnosticId == null || diagnosticId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String normalized = diagnosticId.trim();
        if (!DIAGNOSTIC_ID_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Unsupported diagnostic identifier.");
        }
        return normalized;
    }

    private static String requireSafeText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be null or blank.");
        }
        String normalized = value.trim();
        if (normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " cannot contain null characters.");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
