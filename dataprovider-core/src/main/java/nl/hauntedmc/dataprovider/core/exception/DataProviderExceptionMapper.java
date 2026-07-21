package nl.hauntedmc.dataprovider.core.exception;

import com.mongodb.MongoSecurityException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.MongoWriteException;
import com.mongodb.MongoWriteConcernException;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionRejectedException;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.exception.BackendAuthenticationException;
import nl.hauntedmc.dataprovider.exception.BackendUnavailableException;
import nl.hauntedmc.dataprovider.exception.DataConflictException;
import nl.hauntedmc.dataprovider.exception.DataProviderErrorCode;
import nl.hauntedmc.dataprovider.exception.DataProviderException;
import nl.hauntedmc.dataprovider.exception.DataProviderFailureContext;
import nl.hauntedmc.dataprovider.exception.DataProviderTimeoutException;
import nl.hauntedmc.dataprovider.exception.DataSerializationException;
import nl.hauntedmc.dataprovider.exception.ExecutionOutcome;
import nl.hauntedmc.dataprovider.exception.ProviderClosedException;
import nl.hauntedmc.dataprovider.exception.QueueSaturatedException;
import nl.hauntedmc.dataprovider.exception.RetryAdvice;
import org.bson.codecs.configuration.CodecConfigurationException;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.net.SocketTimeoutException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

/** Internal classification and redaction boundary for public DataProvider failures. */
public final class DataProviderExceptionMapper {

    private DataProviderExceptionMapper() {
    }

    public static DataProviderException translate(Throwable failure, Executor executor, String operationName) {
        Throwable root = unwrap(failure);
        if (root instanceof DataProviderException structured) {
            return structured;
        }
        ExecutionHandle execution = executor instanceof ExecutionHandle handle ? handle : null;
        DatabaseType backend = execution == null ? inferBackend(operationName) : execution.backendType();
        String connection = execution == null ? null : execution.connectionIdentifier();
        DataProviderFailureContext base = DataProviderFailureContext.of(
                backend,
                connection,
                operationName,
                RetryAdvice.CONDITIONAL,
                ExecutionOutcome.UNKNOWN
        );

        if (root instanceof ExecutionRejectedException rejected) {
            Map<String, String> diagnostics = new LinkedHashMap<>();
            diagnostics.put("reason", rejected.reason().name());
            if (execution != null && execution.pluginId() != null) {
                diagnostics.put("plugin", execution.pluginId());
            }
            DataProviderFailureContext context = base.withDiagnostics(diagnostics);
            return switch (rejected.reason()) {
                case RUNTIME_SHUTTING_DOWN, SCOPE_CLOSED -> new ProviderClosedException(
                        "The DataProvider execution scope is closed.",
                        new DataProviderFailureContext(backend, connection, operationName, RetryAdvice.NEVER,
                                ExecutionOutcome.NOT_STARTED, diagnostics, null),
                        safeCause(root)
                );
                case LANE_QUEUE_FULL, PLUGIN_QUEUE_LIMIT, CONNECTION_QUEUE_LIMIT, SUBSCRIPTION_LIMIT ->
                        new QueueSaturatedException(
                                "DataProvider execution capacity is currently exhausted.",
                                new DataProviderFailureContext(backend, connection, operationName, RetryAdvice.SAFE,
                                        ExecutionOutcome.NOT_STARTED, diagnostics, null),
                                safeCause(root)
                        );
            };
        }

        if (root instanceof SQLIntegrityConstraintViolationException || sqlStateStartsWith(root, "23")
                || isMongoDuplicate(root)) {
            return new DataConflictException(
                    "The operation conflicted with existing backend state.",
                    new DataProviderFailureContext(backend, connection, operationName, RetryAdvice.NEVER,
                            ExecutionOutcome.NOT_APPLIED, sqlDiagnostics(root), null),
                    safeCause(root)
            );
        }

        if (isAuthenticationFailure(root)) {
            return new BackendAuthenticationException(
                    "The backend rejected DataProvider authentication.",
                    new DataProviderFailureContext(backend, connection, operationName, RetryAdvice.NEVER,
                            ExecutionOutcome.NOT_STARTED, safeClassDiagnostics(root), null),
                    safeCause(root)
            );
        }

        if (isTimeout(root)) {
            return new DataProviderTimeoutException(
                    "The backend operation timed out.",
                    new DataProviderFailureContext(backend, connection, operationName, RetryAdvice.CONDITIONAL,
                            isReadOperation(operationName) ? ExecutionOutcome.UNKNOWN : ExecutionOutcome.MAY_HAVE_APPLIED,
                            safeClassDiagnostics(root), null),
                    safeCause(root)
            );
        }

        if (isSerializationFailure(root)) {
            return new DataSerializationException(
                    "Data serialization or deserialization failed.",
                    new DataProviderFailureContext(backend, connection, operationName, RetryAdvice.NEVER,
                            ExecutionOutcome.NOT_STARTED, safeClassDiagnostics(root), null),
                    safeCause(root)
            );
        }

        if (isUnavailable(root)) {
            return new BackendUnavailableException(
                    DataProviderErrorCode.BACKEND_UNAVAILABLE,
                    "The configured backend is unavailable.",
                    new DataProviderFailureContext(backend, connection, operationName, RetryAdvice.CONDITIONAL,
                            ExecutionOutcome.UNKNOWN, safeClassDiagnostics(root), null),
                    safeCause(root)
            );
        }

        return new BackendUnavailableException(
                DataProviderErrorCode.BACKEND_UNAVAILABLE,
                "The backend operation failed.",
                base.withDiagnostics(safeClassDiagnostics(root)),
                safeCause(root)
        );
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current.getClass() == RuntimeException.class)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean isAuthenticationFailure(Throwable failure) {
        if (failure instanceof MongoSecurityException || failure instanceof JedisAccessControlException) {
            return true;
        }
        if (failure instanceof SQLException sql) {
            return startsWith(sql.getSQLState(), "28") || sql.getErrorCode() == 1045;
        }
        String name = failure.getClass().getName();
        return name.contains("Authentication") || name.contains("AuthException");
    }

    private static boolean isTimeout(Throwable failure) {
        return failure instanceof SQLTimeoutException || failure instanceof MongoTimeoutException
                || failure instanceof SocketTimeoutException || failure instanceof TimeoutException
                || failure.getClass().getSimpleName().contains("Timeout");
    }

    private static boolean isSerializationFailure(Throwable failure) {
        String name = failure.getClass().getName();
        return failure instanceof CodecConfigurationException || name.startsWith("com.google.gson.")
                || name.contains("JsonProcessingException") || name.contains("SerializationException");
    }

    private static boolean isUnavailable(Throwable failure) {
        if (failure instanceof MongoSocketException || failure instanceof JedisConnectionException) {
            return true;
        }
        if (failure instanceof SQLException sql) {
            return startsWith(sql.getSQLState(), "08");
        }
        String name = failure.getClass().getSimpleName();
        return name.contains("Connection") || name.contains("Socket") || name.contains("ServerSelection");
    }

    private static boolean isMongoDuplicate(Throwable failure) {
        if (failure instanceof MongoWriteException write) {
            return write.getError().getCode() == 11000;
        }
        return failure instanceof MongoWriteConcernException concern
                && concern.getWriteConcernError().getCode() == 11000;
    }

    private static boolean sqlStateStartsWith(Throwable failure, String prefix) {
        return failure instanceof SQLException sql && startsWith(sql.getSQLState(), prefix);
    }

    private static boolean startsWith(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }

    private static Map<String, String> sqlDiagnostics(Throwable failure) {
        LinkedHashMap<String, String> diagnostics = new LinkedHashMap<>(safeClassDiagnostics(failure));
        if (failure instanceof SQLException sql) {
            if (sql.getSQLState() != null && !sql.getSQLState().isBlank()) {
                diagnostics.put("sqlState", sql.getSQLState());
            }
            diagnostics.put("vendorCode", Integer.toString(sql.getErrorCode()));
        }
        return Map.copyOf(diagnostics);
    }

    private static Map<String, String> safeClassDiagnostics(Throwable failure) {
        return Map.of("causeType", failure.getClass().getName());
    }

    private static Throwable safeCause(Throwable failure) {
        return new SafeBackendCause(failure.getClass().getName());
    }

    private static boolean isReadOperation(String operationName) {
        if (operationName == null) {
            return false;
        }
        String lower = operationName.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("get") || lower.contains("find") || lower.contains("query")
                || lower.contains("scan") || lower.contains("range") || lower.contains("health");
    }

    private static DatabaseType inferBackend(String operationName) {
        if (operationName == null) {
            return null;
        }
        if (operationName.startsWith("mysql.")) {
            return DatabaseType.MYSQL;
        }
        if (operationName.startsWith("mongodb.")) {
            return DatabaseType.MONGODB;
        }
        if (operationName.startsWith("redis.messaging.")) {
            return DatabaseType.REDIS_MESSAGING;
        }
        if (operationName.startsWith("redis.")) {
            return DatabaseType.REDIS;
        }
        return null;
    }

    /** Redacted cause preserving only the original exception type. */
    private static final class SafeBackendCause extends RuntimeException {
        private SafeBackendCause(String causeType) {
            super("Backend failure type: " + causeType, null, false, false);
        }
    }
}
