# Best Practices

## API usage

- Use `registerDatabaseOrThrow` and handle its structured failures at the plugin boundary.
- Treat returned `DatabaseProvider` instances as read-only handles; lifecycle is managed through the API.
- Prefer the typed registration and lookup overloads when you need a backend-specific provider interface; use the original two-argument methods when generic `DatabaseProvider` access is enough.
- Use `supportsDataSource()` before requesting JDBC access from generic provider code. Only relational providers expose a `DataSource`.
- Treat database registration as startup wiring, not ad-hoc runtime behavior in hot paths.

## Lifecycle

- Register once during plugin/software startup.
- Unregister on disable.
- `registerDatabaseOrThrow(...)` / `unregisterAllDatabases()` use the default plugin-level owner scope.
- For full plugin/software shutdown across multiple scopes/classes, use `unregisterAllDatabasesForPlugin()`.

## Optional Scoped Ownership

- Use scoped ownership only when one plugin/software process has independently managed components.
- Create a scope facade from `DataProviderAPI.scope("component.name")`.
- Register and release through that scope object so ownership remains isolated.
- Keep scope naming stable and deterministic.
- Each `DataProviderScope` object owns independent registrations, even when another scope has the same public owner name.

## Messaging

- Use one clear message class per channel contract.
- Keep channels stable and namespaced (for example: `proxy.staffchat.message`).
- Retain the returned `Subscription`; the same logical handle remains valid through Redis reconnects.
- Subscriptions are intentionally not `AutoCloseable`. Call `unsubscribe()` (Pub/Sub) or `closeAsync()`
  (durable consumers) and retain the returned future; await it with an application-level timeout whenever
  subsequent work requires the handler to be stopped.
- A handler may initiate its own asynchronous shutdown, but must not wait for it: durable consumers cannot
  finish until the current delivery handler returns.
- Inspect `Subscription.snapshot()` or `MessagingDataAccess.subscriptions()` for state, reconnect count, last failure and downtime.
- Treat a normally completed subscription as explicitly closed and an exceptionally completed subscription as terminally failed.
- Keep handlers fast and non-blocking; use `security.max_queued_messages_per_handler` to cap per-handler backlog.
- Redis Pub/Sub is at-most-once: messages emitted during an outage can disappear even though the listener reconnects.
- Use Redis Streams or another durable acknowledged queue for votes, purchases, punishments and cross-server state changes.
- Durable consumers need stable event IDs and idempotent processing because retries can redeliver work.

## ORM

- Use ORM only for relational providers.
- Keep ORM entity sets explicit and small per context.
- Use `createConfiguredOrmContext(...)` in new code so administrator-configured `orm.schema_mode` remains explicit in the API contract.
- Always call `ORMContext.shutdown()` on disable.

## Configuration

- The generated `default` backend section is a template that is replaced on startup; copy it to a named section before configuring a real connection.
- Keep one connection identifier per concrete config section (for example: `main`).
- Keep identifier names consistent across code and YAML sections.
- Use separate identifiers for different operational requirements (read-only, read-write, analytics).

## Concurrency

- DataProvider connection reuse is reference-counted per `(plugin, type, identifier)`.
- Re-registering the same key does not open a new connection; it acquires another reference.
