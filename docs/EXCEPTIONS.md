# Structured exceptions

DataProvider exposes unchecked structured failures from `nl.hauntedmc.dataprovider.exception`.

## Structured registration APIs

Use `registerDatabaseOrThrow(...)` when startup must distinguish missing configuration, disabled backends, authentication failure, timeout, or backend unavailability. Use `requireRegisteredDatabase(...)` when absence is exceptional.

Registration and lookup always use structured failures. A closed API or scope reports `ProviderClosedException`.

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
- `diagnostic(key)` — Optional lookup for one diagnostic entry
- `diagnosticId()` — validated correlation identifier for operational support
- `failureContext()` — normalized immutable context suitable for passing through another structured boundary

When constructing a failure context incrementally, `withDiagnostic(key, value)` adds or replaces one entry without rebuilding the whole diagnostics map. The existing `withDiagnostics(...)` and `withDiagnosticId(...)` copy methods remain available.

```java
DataProviderFailureContext context = DataProviderFailureContext.of(
        DatabaseType.REDIS,
        "cache",
        "player.lookup",
        RetryAdvice.SAFE,
        ExecutionOutcome.NOT_STARTED
).withDiagnostic("cacheState", "miss");
```

Diagnostic entries are still validated and redacted at the structured exception boundary; convenience methods do not relax the existing safety rules.

## Retry safety

`retryable()` is a conservative convenience method that returns `true` only for `SAFE` failures.
Inspect `retryAdvice()` and `executionOutcome()` before deciding whether to retry conditional failures:

- `SAFE` + `NOT_STARTED` or `NOT_APPLIED`: retrying is normally safe.
- `CONDITIONAL` + `MAY_HAVE_APPLIED`: do not retry blindly; use an idempotency key or verify backend state.
- `CONDITIONAL` + `UNKNOWN`: retry only when the operation is idempotent or backend state has been checked.
- `NEVER`: correct configuration, ownership, authentication, lifecycle, or cleanup state first.

Read timeouts are reported as `SAFE` with outcome `NOT_APPLIED`. Write timeouts remain `CONDITIONAL` with outcome `MAY_HAVE_APPLIED`.

A transaction commit timeout can mean the commit succeeded but its acknowledgement was lost. DataProvider reports this as `DataTransactionException` with phase `COMMIT` and outcome `MAY_HAVE_APPLIED`. Failures while restoring or closing a connection after a successful commit use phase `CLEANUP`, outcome `MAY_HAVE_APPLIED`, and are not retryable.

## Redaction

DataProvider-generated public exception messages, diagnostics, causes, and suppressed cleanup failures do not include passwords, tokens, payloads, query parameter values, raw configuration, or credential-bearing URLs. Public causes preserve the original failure type through a redacted surrogate. Registration lifecycle failures retain their original internal cause for diagnostics while exposing only redacted public metadata.

Rollback and cleanup failures are attached as suppressed structured exceptions without replacing the primary transaction failure. JVM-fatal errors remain primary and are never converted into ordinary DataProvider failures.
