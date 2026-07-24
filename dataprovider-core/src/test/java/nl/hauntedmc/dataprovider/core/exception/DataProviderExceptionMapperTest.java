package nl.hauntedmc.dataprovider.core.exception;

import nl.hauntedmc.dataprovider.core.concurrent.ContextualExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionHandle;
import nl.hauntedmc.dataprovider.core.concurrent.ExecutionRejectedException;
import nl.hauntedmc.dataprovider.database.DatabaseType;
import nl.hauntedmc.dataprovider.exception.BackendAuthenticationException;
import nl.hauntedmc.dataprovider.exception.BackendUnavailableException;
import nl.hauntedmc.dataprovider.exception.DataConflictException;
import nl.hauntedmc.dataprovider.exception.DataProviderException;
import nl.hauntedmc.dataprovider.exception.DataProviderOperationException;
import nl.hauntedmc.dataprovider.exception.DataProviderTimeoutException;
import nl.hauntedmc.dataprovider.exception.ExecutionOutcome;
import nl.hauntedmc.dataprovider.exception.ProviderClosedException;
import nl.hauntedmc.dataprovider.exception.QueueSaturatedException;
import nl.hauntedmc.dataprovider.exception.RetryAdvice;
import org.junit.jupiter.api.Test;

import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DataProviderExceptionMapperTest {

    private final ExecutionHandle execution = new ContextualExecutionHandle(
            ExecutionHandle.direct(), "test-plugin", DatabaseType.MYSQL, "main");

    @Test
    void queueRejectionIsSafeAndRetryable() {
        DataProviderException mapped = DataProviderExceptionMapper.translate(
                new ExecutionRejectedException(
                        ExecutionRejectedException.Reason.PLUGIN_QUEUE_LIMIT,
                        "internal detail"),
                execution,
                "mysql.queryForList"
        );

        QueueSaturatedException saturated = assertInstanceOf(QueueSaturatedException.class, mapped);
        assertEquals(DatabaseType.MYSQL, saturated.backendType());
        assertEquals("main", saturated.connectionIdentifier());
        assertEquals("mysql.queryForList", saturated.operationName());
        assertEquals(RetryAdvice.SAFE, saturated.retryAdvice());
        assertEquals(ExecutionOutcome.NOT_STARTED, saturated.executionOutcome());
        assertEquals("PLUGIN_QUEUE_LIMIT", saturated.diagnostics().get("reason"));
        assertNotNull(saturated.diagnosticId());
        assertFalse(saturated.getMessage().contains("internal detail"));
    }

    @Test
    void closureMapsToNonRetryableProviderFailure() {
        ProviderClosedException closed = assertInstanceOf(
                ProviderClosedException.class,
                DataProviderExceptionMapper.translate(
                        new ExecutionRejectedException(
                                ExecutionRejectedException.Reason.SCOPE_CLOSED,
                                "scope detail"),
                        execution,
                        "mysql.executeUpdate")
        );
        assertEquals(RetryAdvice.NEVER, closed.retryAdvice());
        assertEquals(ExecutionOutcome.NOT_STARTED, closed.executionOutcome());
    }

    @Test
    void resilienceRejectionIsSafeBecauseNoApplicationWorkStarted() {
        BackendUnavailableException unavailable = DataProviderExceptionMapper.resilienceUnavailable(
                DatabaseType.REDIS, "cache", "setKey", "OPEN");

        assertEquals(DatabaseType.REDIS, unavailable.backendType());
        assertEquals("cache", unavailable.connectionIdentifier());
        assertEquals("setKey", unavailable.operationName());
        assertEquals(RetryAdvice.SAFE, unavailable.retryAdvice());
        assertEquals(ExecutionOutcome.NOT_STARTED, unavailable.executionOutcome());
        assertEquals("OPEN", unavailable.diagnostics().get("circuit"));
    }

    @Test
    void explicitProviderClosureIsStructuredAndCannotHaveStartedWork() {
        ProviderClosedException closed = DataProviderExceptionMapper.providerClosed(
                DatabaseType.MONGODB, "players", "insertOne");

        assertEquals(RetryAdvice.NEVER, closed.retryAdvice());
        assertEquals(ExecutionOutcome.NOT_STARTED, closed.executionOutcome());
        assertEquals("insertOne", closed.operationName());
    }

    @Test
    void sqlFailuresAreClassifiedWithoutLeakingMessages() {
        SQLException authentication = new SQLException(
                "password=top-secret jdbc:mysql://secret-host", "28000", 1045);
        BackendAuthenticationException auth = assertInstanceOf(
                BackendAuthenticationException.class,
                DataProviderExceptionMapper.translate(authentication, execution, "mysql.connect")
        );
        assertFalse(auth.getMessage().contains("top-secret"));
        assertFalse(auth.getCause().getMessage().contains("top-secret"));
        assertEquals("28000", auth.diagnostics().get("sqlState"),
                "Authentication diagnostics should retain safe SQL state when available.");

        DataConflictException conflict = assertInstanceOf(
                DataConflictException.class,
                DataProviderExceptionMapper.translate(
                        new SQLIntegrityConstraintViolationException("duplicate secret", "23000", 1062),
                        execution,
                        "mysql.executeInsert")
        );
        assertEquals(ExecutionOutcome.NOT_APPLIED, conflict.executionOutcome());

        DataProviderTimeoutException writeTimeout = assertInstanceOf(
                DataProviderTimeoutException.class,
                DataProviderExceptionMapper.translate(
                        new SQLTimeoutException("write timed out", "HYT00"),
                        execution,
                        "mysql.executeUpdate")
        );
        assertEquals(ExecutionOutcome.MAY_HAVE_APPLIED, writeTimeout.executionOutcome());
        assertEquals(RetryAdvice.CONDITIONAL, writeTimeout.retryAdvice());
    }

    @Test
    void readTimeoutIsSafeToRetryAndCannotHaveAppliedData() {
        DataProviderTimeoutException timeout = assertInstanceOf(
                DataProviderTimeoutException.class,
                DataProviderExceptionMapper.translate(
                        new SQLTimeoutException("read timed out", "HYT00"),
                        execution,
                        "mysql.queryForList")
        );

        assertEquals(RetryAdvice.SAFE, timeout.retryAdvice());
        assertEquals(ExecutionOutcome.NOT_APPLIED, timeout.executionOutcome());
    }

    @Test
    void unclassifiedDriverFailureUsesGenericOperationCategory() {
        DataProviderOperationException failure = assertInstanceOf(
                DataProviderOperationException.class,
                DataProviderExceptionMapper.translate(
                        new SQLException("syntax near password=secret", "42000", 1064),
                        execution,
                        "mysql.executeUpdate")
        );

        assertEquals(RetryAdvice.CONDITIONAL, failure.retryAdvice());
        assertEquals(ExecutionOutcome.UNKNOWN, failure.executionOutcome());
        assertEquals("42000", failure.diagnostics().get("sqlState"));
        assertFalse(failure.getMessage().contains("secret"));
        assertFalse(failure.getCause().getMessage().contains("secret"));
    }
}
