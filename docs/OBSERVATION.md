# Operation observation

DataProvider exposes an optional, vendor-neutral observation SPI for infrastructure integrations that need to measure or trace plugin-scoped data operations without coupling DataProvider to a telemetry implementation.

## Attach an observer

Attach an observer to a plugin-bound facade and retain that facade for the plugin lifecycle:

```java
DataProviderAPI api = supplier.dataProviderApiFor(this)
        .withObserver(observer);
```

The observer is local to that facade. It propagates to child `DataProviderScope` instances and handles obtained through that facade; it is never installed globally and does not affect other plugins using the same DataProvider runtime.

The public SPI consists of:

- `DataProviderObserver`, which starts one observation;
- `DataProviderObservation`, which receives exactly one success or failure terminal callback for an observation that was started successfully;
- `DataProviderOperationContext`, which contains stable plugin ownership, public owner scope, backend type, and operation name.

## Metadata boundary

`DataProviderOperationContext` intentionally contains only bounded operational metadata:

- platform-derived plugin id;
- public lifecycle owner scope;
- `DatabaseType`;
- a DataProvider-owned operation name such as `database.register`, `relational.queryForSingle`, `keyvalue.getKey`, or `messaging.publish`.

It deliberately does **not** expose connection identifiers, SQL/query text, query parameters, Redis keys or patterns, collection names, messaging destinations or streams, payloads, credentials, or player data. Internal UUID-backed registration scopes are also never exposed; scoped facades report their public `OwnerScope` instead.

## Completion semantics

Synchronous operations finish their observation before returning or throwing. Operations returning a `CompletionStage` finish only when that stage completes, so timing includes the actual asynchronous backend work rather than only task submission.

Observer callbacks must be thread-safe and non-blocking. DataProvider isolates runtime exceptions raised by observer start/success/failure callbacks: instrumentation cannot turn a successful data operation into a failure or replace the original data-operation exception.

## Current operation coverage

The initial SPI observes meaningful API boundaries rather than every internal method:

- database registration and single-registration removal;
- relational, document, key-value, schema, Pub/Sub, and durable-messaging data-access operations;
- subscription shutdown/acknowledgement operations;
- ORM transaction execution.

Registry lookups, diagnostic getters, connection identifiers, raw JDBC calls through an exposed `DataSource`, bulk teardown without a single backend identity, and internal health/recovery mechanics are intentionally outside the initial contract.

DataProvider has no OpenTelemetry dependency. HauntedObservability can implement this SPI later while DataProvider remains usable with the built-in no-op path.
