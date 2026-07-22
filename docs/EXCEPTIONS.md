# Structured exceptions

DataProvider exposes unchecked structured failures from `nl.hauntedmc.dataprovider.exception`.

## Strict and compatibility APIs

Use `registerDatabaseOrThrow(...)` when startup must distinguish missing configuration, disabled backends, authentication failure, timeout, or backend unavailability. Use `requireRegisteredDatabase(...)` when absence is exceptional.

The original `registerDatabase(...)` and optional helpers remain available for compatibility. They continue returning `null` or `Optional.empty()` and intentionally discard the failure category after DataProvider records registration failures internally.

Legacy methods retain their previous lifecycle behavior, including `IllegalStateException` after their API or scope has closed. The new strict registration and lookup methods report closure as `ProviderClosedException`.

Caller input validation remains distinct from backend failure classification. Invalid identifiers, unsupported document values, null arguments, and similar programming errors continue to use standard validation exceptions such as `IllegalArgumentException` and `NullPointerException`.

## Common handling

```java
try {
    DatabaseProvider provider = api.registerDatabaseOrThrow(DatabaseType.MYSQL, "main");
} catch (BackendAuthenticationException exception) {
    // Configuration intervention is required; retrying unchanged credentials is not useful.
} catch (BackendUnavailableException exception) {
    // The backend is disabled or unreachable.
} catch (DataProviderOperationException exception) {
    // The operation failed without matching a more specific public category.
}
```

All structured exceptions expose:

- `errorCode()` — stable machine-readable category
- `backendType()` — backend involved, when applicable
- `connectionIdentifier()` — safe logical identifier, never a connection URL
- `operationName()` — stable operation identifier
- `retryAdvice()` — `NEVER`, `SAFE`, or `CONDITIONAL`
- `executionOutcome()` — whether the operation started or may already have applied
- `diagnostics()` — immutable allowlisted metadata
- `diagnosticId()` — validated correlation identifier for operational support

## Retry safety

`retryable()` is a convenience method. Prefer `retryAdvice()` and `executionOutcome()` for writes:

- `SAFE` + `NOT_STARTED` or `NOT_APPLIED`: retrying is normally safe.
- `CONDITIONAL` + `MAY_HAVE_APPLIED`: do not retry blindly; use an idempotency key or verify backend state.
- `CONDITIONAL` + `UNKNOWN`: retry only when the operation is idempotent or backend state has been checked.
- `NEVER`: correct configuration, ownership, authentication, lifecycle, or cleanup state first.

Read timeouts are reported as `SAFE` with outcome `NOT_APPLIED`. Write timeouts remain `CONDITIONAL` with outcome `MAY_HAVE_APPLIED`.

A transaction commit timeout can mean the commit succeeded but its acknowledgement was lost. DataProvider reports this as `DataTransactionException` with phase `COMMIT` and outcome `MAY_HAVE_APPLIED`. Failures while restoring or closing a connection after a successful commit use phase `CLEANUP`, outcome `MAY_HAVE_APPLIED`, and are not retryable.

## Redaction

DataProvider-generated public exception messages, diagnostics, causes, and suppressed cleanup failures do not include passwords, tokens, payloads, query parameter values, raw configuration, or credential-bearing URLs. Public causes preserve the original failure type through a redacted surrogate. Registration lifecycle failures retain their original internal cause for diagnostics while exposing only redacted public metadata.

Rollback and cleanup failures are attached as suppressed structured exceptions without replacing the primary transaction failure. JVM-fatal errors remain primary and are never converted into ordinary DataProvider failures.
