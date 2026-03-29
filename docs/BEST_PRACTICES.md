# Best Practices

## API usage

- Prefer `registerDatabaseOptional`, `registerDatabaseAs`, and `registerDataAccess` over raw nullable APIs.
- Treat returned `DatabaseProvider` instances as read-only handles; lifecycle is managed through the API.
- Prefer `getDataAccessOptional(...)` instead of manual casts.
- Treat database registration as startup wiring, not ad-hoc runtime behavior in hot paths.

## Lifecycle

- Register once during plugin/software startup.
- Unregister on disable.
- `registerDatabase(...)` / `unregisterAllDatabases()` use the default plugin-level owner scope.
- For full plugin/software shutdown across multiple scopes/classes, use `unregisterAllDatabasesForPlugin()`.

## Optional Scoped Ownership

- Use scoped ownership only when one plugin/software process has independently managed components.
- Create a scope facade from `DataProviderAPI.scope("component.name")`.
- Register and release through that scope object so ownership remains isolated.
- Keep scope naming stable and deterministic.

## Messaging

- Use one clear message class per channel contract.
- Keep channels stable and namespaced (for example: `proxy.staffchat.message`).
- Handle parse/dispatch failures as non-fatal.
- Keep handlers fast and non-blocking; use `security.max_queued_messages_per_handler` to cap per-handler backlog.

## ORM

- Use ORM only for relational providers.
- Keep ORM entity sets explicit and small per context.
- Always call `ORMContext.shutdown()` on disable.

## Configuration

- Keep one connection identifier per concrete config section (for example: `default`).
- Keep identifier names consistent across code and YAML sections.
- Use separate identifiers for different operational requirements (read-only, read-write, analytics).

## Concurrency

- DataProvider connection reuse is reference-counted per `(plugin, type, identifier)`.
- Re-registering the same key does not open a new connection; it acquires another reference.
