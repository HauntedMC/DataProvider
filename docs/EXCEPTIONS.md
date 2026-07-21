# Structured exceptions

DataProvider exposes unchecked structured failures from `nl.hauntedmc.dataprovider.exception`.

## Strict and compatibility APIs

Use `registerDatabaseOrThrow(...)` when startup must distinguish missing configuration, disabled backends, authentication failure, timeout, or backend unavailability. Use `requireRegisteredDatabase(...)` when absence is exceptional.

The original `registerDatabase(...)` and optional helpers remain available for compatibility. They continue returning `null` or `Optional.empty()` and intentionally discard the failure category after DataProvider records it internally.

## Common handling

```java
try {
    DatabaseProvider provider = api.registerDatabaseOrThrow(DatabaseType.MYSQL, "main");
} catch (BackendAuthenticationException exception) {
    // Configuration intervention is required; retrying unchanged credentials is not useful.
} catch (BackendUnavailableException exception) {
    // Consult retryAdvice() and executionOutcome() before retrying.
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
- `diagnosticId()` — correlation identifier for operational support

## Retry safety

`retryable()` is a convenience method. Prefer `retryAdvice()` and `executionOutcome()` for writes:

- `SAFE` + `NOT_STARTED`: retrying is normally safe.
- `CONDITIONAL` + `MAY_HAVE_APPLIED`: do not retry blindly; use an idempotency key or verify backend state.
- `NEVER`: correct configuration, ownership, authentication, or lifecycle state first.

A transaction commit timeout can mean the commit succeeded but its acknowledgement was lost. DataProvider reports this as `DataTransactionException` with phase `COMMIT` and outcome `MAY_HAVE_APPLIED`.

## Redaction

Public exception messages, diagnostics, and causes never include passwords, tokens, payloads, query parameter values, raw configuration, or credential-bearing URLs. Public causes preserve the original failure type through a redacted surrogate. Raw backend failures remain confined to DataProvider lifecycle diagnostics and internal logging.

Rollback failures are attached as suppressed structured exceptions without replacing the primary transaction failure.
