# Architecture Overview

## Core Pattern

`DataProvider` exposes one API (`DataProviderAPI`) over a shared registry.
Plugins register by backend type + identifier and get typed access interfaces back.

Main modules:

- `api`: public registration/lookup surface
- `internal`: registry, factory, config mapping, identity, and lifecycle logic
- `database.*`: backend implementations and typed data-access contracts
- `platform.bukkit` / `platform.velocity`: platform bootstrap and caller context resolution

## Registration Model

1. Plugin asks for a backend registration (`registerDatabase*` / `registerDataAccess`).
2. Caller identity is resolved from runtime plugin context.
3. Config section is resolved by backend type + identifier.
4. Registry returns an existing live provider or creates one through `DatabaseFactory`.
5. Registration is reference-counted and reused for identical keys in the same plugin context.

## Provider Types

- Relational: MySQL (`RelationalDataAccess`)
- Document: MongoDB (`DocumentDataAccess`)
- Key-value: Redis (`KeyValueDataAccess`)
- Messaging: Redis Pub/Sub (`MessagingDataAccess`)

## Lifecycle Safety

- Per-caller ownership checks gate unregister operations.
- Stale/disconnected providers are evicted from registry lookup paths.
- Shutdown hooks unregister or stop backend resources cleanly.
- Bounded executors are used for asynchronous backend work queues.

## ORM Integration

`ORMContext` is an optional layer for relational providers.
Schema mode is controlled through `config.yml` (`orm.schema_mode`).

## Security Expectations

- Treat database and messaging payload input as untrusted.
- Use TLS transport options for production backends.
- Never log credentials or secrets.
- Keep plugin boundaries explicit and avoid cross-plugin identity leakage.
