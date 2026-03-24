# Best Practices

## API usage

- Prefer `registerDatabaseOptional`, `registerDatabaseAs`, and `registerDataAccess` over raw nullable APIs.
- Prefer `getDataAccessOptional(...)` instead of manual casts.
- Treat database registration as startup wiring, not ad-hoc runtime behavior in hot paths.

## Lifecycle

- Register once during feature/plugin init.
- Unregister on disable.
- If you run multiple feature modules in one plugin, prefer releasing only the connections each feature acquired.
- Use `unregisterAllDatabases()` only when shutting down the entire plugin context.

## Messaging

- Use one clear message class per channel contract.
- Keep channels stable and namespaced (for example: `proxy.staffchat.message`).
- Handle parse/dispatch failures as non-fatal.

## ORM

- Use ORM only for relational providers.
- Keep ORM entity sets explicit and small per context.
- Always call `ORMContext.shutdown()` on disable.

## Configuration

- Keep one connection identifier per concrete config section (for example: `default`, `player_data_rw`).
- Use separate identifiers for different operational requirements (read-only, read-write, analytics).

## Concurrency

- DataProvider connection reuse is reference-counted per `(plugin, type, identifier)`.
- Re-registering the same key does not open a new connection; it acquires another reference.
