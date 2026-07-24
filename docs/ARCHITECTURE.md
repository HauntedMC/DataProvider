# Architecture Overview

## Core Pattern

`DataProvider` exposes one API (`DataProviderAPI`) over a shared registry.
Plugins register by backend type + identifier and get typed access interfaces back.

Maven modules define the architecture boundary:

- `dataprovider-api`: public registration/lookup contracts, data-access interfaces, models, logging abstraction, and ORM contract/factory.
- `dataprovider-core`: registry, configuration, caller identity, storage implementations, and ORM implementation.
- `dataprovider-platform-common`: shared runtime lifecycle, command service, and host logger adapters.
- `dataprovider-platform-paper` / `dataprovider-platform-velocity`: host-specific bootstrap, command wiring, and caller resolution.

Public packages stay under `nl.hauntedmc.dataprovider.api` and `nl.hauntedmc.dataprovider.database`; implementation packages are explicitly rooted at `nl.hauntedmc.dataprovider.core` or `nl.hauntedmc.dataprovider.platform`.

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
- Temporary local or remote outages never evict a registration; recovery is managed behind its stable logical handle.
- Shutdown hooks unregister or stop backend resources cleanly.
- Bounded executors are used for asynchronous backend work queues.
- Platform runtime wrappers use a shared thread-safe lifecycle holder to prevent stale instance leaks across enable/disable cycles.

## Platform Layer Design

- `PlatformDataProviderRuntime` centralizes bootstrap shutdown behavior and startup rollback handling.
- Platform command adapters delegate to a shared `DataProviderCommandService` so Bukkit and Velocity command behavior stays identical.
- Command service exposes diagnostics-focused admin commands (`status`, `config`, `reload`) with permission-gated filtering. Status reads cached remote health and triggers refresh probes asynchronously, so platform server threads never perform remote health checks.
- API discovery is platform-native: Bukkit registers `DataProviderAPI` in `ServicesManager`; Velocity exposes `DataProviderApiSupplier` on plugin instance.
- Platform-specific wrappers only map host APIs to shared internals (logger, command registration, event/plugin lifecycle hooks).

## ORM Integration

`ORMContext` is a public API contract for relational providers (`api.orm`). Create it through
`DataProviderAPI.createOrmContext(...)`; the Core module supplies the Hibernate implementation.
Schema mode is selected explicitly by the consuming plugin.

## Security Expectations

- Treat database and messaging payload input as untrusted.
- Use TLS transport options for production backends.
- Never log credentials or secrets.
- Keep plugin boundaries explicit and avoid cross-plugin identity leakage.
## Runtime Resilience

Registrations have two independent dimensions: lifecycle (`NEW` through `CLOSED`) and runtime health
(`HEALTHY`, `DEGRADED`, `RECOVERING`, `UNAVAILABLE`). A core-owned bounded worker/scheduler performs
coalesced health probes and recovery attempts per physical backend resource. A transient outage does not evict a
registry slot. Stable logical provider, data-access, schema-manager, and messaging-access delegates
resolve the current scoped physical view, so a locally recreated pool/client remains reachable through
existing consumer references. Drivers/pools are allowed to recover normally before local recreation;
a repeated failed recovery recreates a still-locally-open client or pool that has become unusable.

The status command consumes cached snapshots and requests only stale refreshes; it never performs
remote I/O on a platform thread. Snapshot diagnostics include lifecycle, health, circuit, probe time,
failure/recovery counts, backoff, and next recovery attempt. Messaging subscription resubscription and
endpoint migration are intentionally outside this layer.
