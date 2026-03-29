# Architecture Overview

## Core Pattern

`DataProvider` exposes one API (`DataProviderAPI`) over a shared registry.
Plugins register by backend type + identifier and get typed access interfaces back.

Main modules:

- `api`: public registration/lookup surface
- `internal`: registry, factory, config mapping, identity, and lifecycle logic
- `database.*`: backend implementations and typed data-access contracts
- `platform.internal`: shared platform runtime lifecycle and command behavior
- `platform.bukkit` / `platform.velocity`: platform adapters (bootstrap, command wiring, caller context resolution)

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
- Reference ownership is tracked by owner scope.
- Default API methods use plugin-level owner scope for predictable lifecycle behavior.
- If one plugin/software process multiplexes multiple components through one wrapper class, use optional scoped lifecycle facades (`DataProviderAPI.scope(...)`) to preserve component isolation.
- Explicit plugin-wide cleanup is available for shutdown flows that span multiple caller scopes.
- Stale/disconnected providers are evicted from registry lookup paths.
- Shutdown hooks unregister or stop backend resources cleanly.
- Bounded executors are used for asynchronous backend work queues.
- Platform runtime wrappers use a shared thread-safe lifecycle holder to prevent stale instance leaks across enable/disable cycles.

## Platform Layer Design

- `PlatformDataProviderRuntime` centralizes bootstrap shutdown behavior and startup rollback handling.
- Platform command adapters delegate to a shared `DataProviderCommandService` so Bukkit and Velocity command behavior stays identical.
- Command service exposes diagnostics-focused admin commands (`status`, `config`, `reload`) with permission-gated filtering and runtime health summaries.
- API discovery is platform-native: Bukkit registers `DataProviderAPI` in `ServicesManager`; Velocity exposes `DataProviderApiSupplier` on plugin instance.
- Platform-specific wrappers only map host APIs to shared internals (logger, command registration, event/plugin lifecycle hooks).

## ORM Integration

`ORMContext` is an optional layer for relational providers.
Schema mode is controlled through `config.yml` (`orm.schema_mode`).

## Security Expectations

- Treat database and messaging payload input as untrusted.
- Use TLS transport options for production backends.
- Never log credentials or secrets.
- Keep plugin boundaries explicit and avoid cross-plugin identity leakage.
