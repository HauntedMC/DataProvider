package nl.hauntedmc.dataprovider.core.exception;

import com.google.gson.JsonParseException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.MongoWriteException;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionRejectedException;
import nl.hauntedmc.dataprovider.core.ConnectionAccessDeniedException;
import nl.hauntedmc.dataprovider.core.InvalidConnectionAccessPolicyException;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.exception.BackendAuthenticationException;
import nl.hauntedmc.dataprovider.exception.DataProviderAccessDeniedException;
import nl.hauntedmc.dataprovider.exception.BackendUnavailableException;
import nl.hauntedmc.dataprovider.exception.DataConflictException;
import nl.hauntedmc.dataprovider.exception.DataProviderConfigurationException;
import nl.hauntedmc.dataprovider.exception.DataProviderErrorCode;
import nl.hauntedmc.dataprovider.exception.DataProviderException;
import nl.hauntedmc.dataprovider.exception.DataProviderFailureContext;
import nl.hauntedmc.dataprovider.exception.DataProviderOperationException;
import nl.hauntedmc.dataprovider.exception.DataProviderRegistrationException;
import nl.hauntedmc.dataprovider.exception.DataProviderTimeoutException;
import nl.hauntedmc.dataprovider.exception.DataSerializationException;
import nl.hauntedmc.dataprovider.exception.DataTransactionException;
import nl.hauntedmc.dataprovider.exception.ExecutionOutcome;
import nl.hauntedmc.dataprovider.exception.ProviderClosedException;
import nl.hauntedmc.dataprovider.exception.QueueSaturatedException;
import nl.hauntedmc.dataprovider.exception.RetryAdvice;
import nl.hauntedmc.dataprovider.exception.TransactionPhase;
import org.bson.codecs.configuration.CodecConfigurationException;
import redis.clients.jedis.exceptions.JedisAccessControlException;
import redis.clients.jedis.exceptions.JedisConnectionException;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/** Internal classification and redaction boundary for public DataProvider failures. */
public final class DataProviderExceptionMapper {
    private static final Set<String> READ_OPERATIONS = Set.of(
            "mongodb.findMany",
            "mongodb.findOne",
            "mysql.queryForList",
            "mysql.queryForSingle",
            "mysql.queryForSingleValue",
            "mysql.schema.tableExists",
            "redis.getKey",
            "redis.hgetAll",
            "redis.queryByPattern",
            "redis.smembers",
            "redis.zrangeByScore"
    );

    private DataProviderExceptionMapper() {
    }

    public static DataProviderException translate(Throwable failure, Executor executor, String operationName) {
        Throwable root = unwrapAsync(Objects.requireNonNull(failure, "Failure cannot be null."));
        if (root instanceof DataProviderException structured) {
            return structured;
        }
        ExecutionHandle execution = executor instanceof ExecutionHandle handle ? handle : null;
        DatabaseType backend = execution == null ? inferBackend(operationName) : execution.backendType();
        String connection = execution == null ? null : execution.connectionIdentifier();

        if (root instanceof ExecutionRejectedException rejected) {
            Map<String, String> diagnostics = rejectionDiagnostics(rejected, execution);
            return switch (rejected.reason()) {
                case RUNTIME_SHUTTING_DOWN, SCOPE_CLOSED -> new ProviderClosedException(
                        "The DataProvider execution scope is closed.",
                        context(backend, connection, operationName, RetryAdvice.NEVER,
                                ExecutionOutcome.NOT_STARTED, diagnostics), safeCause(root));
                case LANE_QUEUE_FULL, PLUGIN_QUEUE_LIMIT, CONNECTION_QUEUE_LIMIT, SUBSCRIPTION_LIMIT ->
                        new QueueSaturatedException(
                                "DataProvider execution capacity is currently exhausted.",
                                context(backend, connection, operationName, RetryAdvice.SAFE,
                                        ExecutionOutcome.NOT_STARTED, diagnostics), safeCause(root));
            };
        }
        if (isConflict(root)) {
            return new DataConflictException(
                    "The operation conflicted with existing backend state.",
                    context(backend, connection, operationName, RetryAdvice.NEVER,
                            ExecutionOutcome.NOT_APPLIED, diagnosticsFor(root)), safeCause(root));
        }
        if (isAuthenticationFailure(root)) {
            return new BackendAuthenticationException(
                    "The backend rejected DataProvider authentication.",
                    context(backend, connection, operationName, RetryAdvice.NEVER,
                            ExecutionOutcome.NOT_STARTED, diagnosticsFor(root)), safeCause(root));
        }
        if (isTimeout(root)) {
            boolean readOperation = isReadOperation(operationName);
            return new DataProviderTimeoutException(
                    "The backend operation timed out.",
                    context(backend, connection, operationName,
                            readOperation ? RetryAdvice.SAFE : RetryAdvice.CONDITIONAL,
                            readOperation ? ExecutionOutcome.NOT_APPLIED : ExecutionOutcome.MAY_HAVE_APPLIED,
                            diagnosticsFor(root)), safeCause(root));
        }
        if (isSerializationFailure(root)) {
            return new DataSerializationException(
                    "Data serialization or deserialization failed.",
                    context(backend, connection, operationName, RetryAdvice.NEVER,
                            ExecutionOutcome.NOT_STARTED, diagnosticsFor(root)), safeCause(root));
        }
        if (isUnavailable(root)) {
            return unavailable(backend, connection, operationName, root);
        }
        return new DataProviderOperationException(
                "The backend operation failed.",
                context(backend, connection, operationName, RetryAdvice.CONDITIONAL,
                        ExecutionOutcome.UNKNOWN, diagnosticsFor(root)), safeCause(root));
    }

    public static DataProviderException registrationFailure(
            Throwable failure, DatabaseType backend, String connectionIdentifier, String operationName) {
        Throwable root = unwrapRegistration(failure);
        if (root instanceof DataProviderException structured) {
            return structured;
        }
        if (root == null) {
            return registrationException(backend, connectionIdentifier, operationName, Map.of(), null);
        }
        if (root instanceof MissingConfigurationFailure) {
            return new DataProviderConfigurationException(
                    DataProviderErrorCode.CONFIGURATION_MISSING,
                    "No configuration exists for the requested database connection.",
                    context(backend, connectionIdentifier, operationName, RetryAdvice.NEVER,
                            ExecutionOutcome.NOT_STARTED, diagnosticsFor(root)), safeCause(root));
        }
        if (root instanceof ConnectionAccessDeniedException) {
            return new DataProviderAccessDeniedException(
                    "The calling plugin is not allowed to access the requested database connection.",
                    context(backend, connectionIdentifier, operationName, RetryAdvice.NEVER,
                            ExecutionOutcome.NOT_STARTED, Map.of()),
                    null
            );
        }
        if (root instanceof InvalidConnectionAccessPolicyException) {
            return new DataProviderConfigurationException(
                    DataProviderErrorCode.CONFIGURATION_INVALID,
                    "The requested database connection has an invalid access policy.",
                    context(backend, connectionIdentifier, operationName, RetryAdvice.NEVER,
                            ExecutionOutcome.NOT_STARTED, Map.of()),
                    safeCause(root)
            );
        }
        DataProviderException mapped = translate(
                root, new RegistrationExecutionHandle(backend, connectionIdentifier), operationName);
        if (mapped instanceof DataProviderOperationException) {
            return registrationException(
                    backend, connectionIdentifier, operationName,
                    Map.of("causeCode", mapped.errorCode().name()), mapped);
        }
        return mapped;
    }

    public static BackendUnavailableException backendDisabled(DatabaseType backend, String connectionIdentifier) {
        return new BackendUnavailableException(
                DataProviderErrorCode.BACKEND_DISABLED,
                "The requested database backend is disabled.",
                context(backend, connectionIdentifier, "registerDatabase", RetryAdvice.NEVER,
                        ExecutionOutcome.NOT_STARTED, Map.of()), null);
    }

    /** Failure-fast mapping used when the resilience circuit has rejected work before it was submitted. */
    public static BackendUnavailableException resilienceUnavailable(
            DatabaseType backend, String connectionIdentifier, String operation, String circuit
    ) {
        return new BackendUnavailableException(
                DataProviderErrorCode.BACKEND_UNAVAILABLE,
                "The configured backend is temporarily unavailable.",
                context(backend, connectionIdentifier, operation, RetryAdvice.SAFE,
                        ExecutionOutcome.NOT_STARTED, Map.of("circuit", circuit)), null);
    }

    /** Failure mapping for an operation attempted through a provider whose lifecycle has ended. */
    public static ProviderClosedException providerClosed(
            DatabaseType backend, String connectionIdentifier, String operation
    ) {
        return new ProviderClosedException(
                "The DataProvider provider is closed.",
                context(backend, connectionIdentifier, operation, RetryAdvice.NEVER,
                        ExecutionOutcome.NOT_STARTED, Map.of()), null);
    }

    public static DataProviderConfigurationException configurationFailure(Throwable failure, String operationName) {
        Throwable root = unwrapAsync(failure);
        return new DataProviderConfigurationException(
                DataProviderErrorCode.CONFIGURATION_INVALID,
                "DataProvider configuration is invalid.",
                context(null, null, operationName, RetryAdvice.NEVER,
                        ExecutionOutcome.NOT_STARTED, diagnosticsFor(root)), safeCause(root));
    }

    public static DataTransactionException transactionFailure(
            Throwable failure,
            Executor executor,
            String operationName,
            TransactionPhase phase,
            ExecutionOutcome outcome
    ) {
        DataProviderException mapped = translate(failure, executor, operationName);
        return new DataTransactionException(
                "The database transaction failed during " + phase.name().toLowerCase(Locale.ROOT) + ".",
                phase,
                context(mapped.backendType(), mapped.connectionIdentifier(), operationName,
                        phase == TransactionPhase.COMMIT ? RetryAdvice.CONDITIONAL : RetryAdvice.NEVER,
                        outcome, Map.of("phase", phase.name(), "causeCode", mapped.errorCode().name())),
                mapped
        );
    }

    public static MissingConfigurationFailure missingConfigurationFailure() {
        return new MissingConfigurationFailure();
    }

    private static DataProviderRegistrationException registrationException(
            DatabaseType backend,
            String connectionIdentifier,
            String operationName,
            Map<String, String> diagnostics,
            Throwable cause
    ) {
        return new DataProviderRegistrationException(
                "Database registration failed.",
                context(backend, connectionIdentifier, operationName, RetryAdvice.CONDITIONAL,
                        ExecutionOutcome.NOT_STARTED, diagnostics), cause);
    }

    private static BackendUnavailableException unavailable(
            DatabaseType backend, String connection, String operationName, Throwable root) {
        return new BackendUnavailableException(
                DataProviderErrorCode.BACKEND_UNAVAILABLE,
                "The configured backend is unavailable.",
                context(backend, connection, operationName, RetryAdvice.CONDITIONAL,
                        ExecutionOutcome.UNKNOWN, diagnosticsFor(root)), safeCause(root));
    }

    private static DataProviderFailureContext context(
            DatabaseType backend,
            String connection,
            String operation,
            RetryAdvice retry,
            ExecutionOutcome outcome,
            Map<String, String> diagnostics
    ) {
        return new DataProviderFailureContext(backend, connection, operation, retry, outcome, diagnostics, null);
    }

    private static Throwable unwrapAsync(Throwable failure) {
        if (failure == null) {
            return null;
        }
        Throwable current = failure;
        while (current.getCause() != null && (current instanceof CompletionException
                || current instanceof ExecutionException
                || current.getClass() == RuntimeException.class)) {
            current = current.getCause();
        }
        return current;
    }

    private static Throwable unwrapRegistration(Throwable failure) {
        Throwable current = unwrapAsync(failure);
        while (current instanceof IllegalStateException && current.getCause() != null) {
            current = unwrapAsync(current.getCause());
        }
        return current;
    }

    private static boolean isConflict(Throwable failure) {
        return hasCause(failure, candidate ->
                candidate instanceof SQLIntegrityConstraintViolationException
                        || sqlStateStartsWith(candidate, "23")
                        || candidate instanceof MongoWriteException write && write.getError().getCode() == 11000
        );
    }

    private static boolean isAuthenticationFailure(Throwable failure) {
        return hasCause(failure, candidate ->
                candidate instanceof MongoSecurityException
                        || candidate instanceof JedisAccessControlException
                        || candidate instanceof SQLException sql
                        && (startsWith(sql.getSQLState(), "28") || sql.getErrorCode() == 1045)
        );
    }

    private static boolean isTimeout(Throwable failure) {
        return hasCause(failure, candidate ->
                candidate instanceof SQLTimeoutException
                        || candidate instanceof MongoTimeoutException
                        || candidate instanceof SocketTimeoutException
                        || candidate instanceof TimeoutException
        );
    }

    private static boolean isSerializationFailure(Throwable failure) {
        return hasCause(failure, candidate ->
                candidate instanceof CodecConfigurationException || candidate instanceof JsonParseException
        );
    }

    private static boolean isUnavailable(Throwable failure) {
        return hasCause(failure, candidate ->
                candidate instanceof MongoSocketException
                        || candidate instanceof JedisConnectionException
                        || candidate instanceof ConnectException
                        || candidate instanceof SocketException
                        || candidate instanceof SQLException sql && startsWith(sql.getSQLState(), "08")
        );
    }

    private static boolean hasCause(Throwable failure, Predicate<Throwable> predicate) {
        IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
        Throwable current = failure;
        while (current != null && seen.put(current, Boolean.TRUE) == null) {
            if (predicate.test(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean sqlStateStartsWith(Throwable failure, String prefix) {
        return failure instanceof SQLException sql && startsWith(sql.getSQLState(), prefix);
    }

    private static boolean startsWith(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }

    private static Map<String, String> rejectionDiagnostics(
            ExecutionRejectedException rejection, ExecutionHandle execution) {
        LinkedHashMap<String, String> diagnostics = new LinkedHashMap<>();
        diagnostics.put("reason", rejection.reason().name());
        if (execution != null && execution.pluginId() != null) {
            diagnostics.put("plugin", execution.pluginId());
        }
        return Map.copyOf(diagnostics);
    }

    private static Map<String, String> diagnosticsFor(Throwable failure) {
        LinkedHashMap<String, String> diagnostics = new LinkedHashMap<>();
        if (failure != null) {
            diagnostics.put("causeType", failure.getClass().getName());
        }
        if (failure instanceof SQLException sql) {
            if (sql.getSQLState() != null && !sql.getSQLState().isBlank()) {
                diagnostics.put("sqlState", sql.getSQLState());
            }
            diagnostics.put("vendorCode", Integer.toString(sql.getErrorCode()));
        }
        return Map.copyOf(diagnostics);
    }

    private static Throwable safeCause(Throwable failure) {
        return failure == null ? null : new SafeBackendCause(failure.getClass().getName());
    }

    private static boolean isReadOperation(String operationName) {
        if (operationName == null) {
            return false;
        }
        return READ_OPERATIONS.contains(operationName) || operationName.endsWith(".health");
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

    private record RegistrationExecutionHandle(DatabaseType backendType, String connectionIdentifier)
            implements ExecutionHandle {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public nl.hauntedmc.dataprovider.core.concurrent.ExecutionMetricsSnapshot metrics() {
            return new nl.hauntedmc.dataprovider.core.concurrent.ExecutionMetricsSnapshot(
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        @Override public boolean isClosed() { return false; }
        @Override public void close() { }
    }

    public static final class MissingConfigurationFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private MissingConfigurationFailure() {
            super("Missing database configuration", null, false, false);
        }
    }

    private static final class SafeBackendCause extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private SafeBackendCause(String causeType) {
            super("Backend failure type: " + causeType, null, false, false);
        }
    }
}
