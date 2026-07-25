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

1. Plugin asks for a backend registration through `registerDatabaseOrThrow`.
2. The platform-issued identity captured by the bound API facade supplies plugin ownership.
3. Config section is resolved by backend type + identifier.
4. Registry returns an existing live provider or creates one through `DatabaseFactory`.
5. Registration is reference-counted and reused for identical keys in the same plugin context.

## Provider Types

- Relational: MySQL (`RelationalDataAccess`)
- Document: MongoDB (`DocumentDataAccess`)
- Key-value: Redis (`KeyValueDataAccess`)
- Messaging: Redis Pub/Sub (`MessagingDataAccess`)

## Lifecycle Safety

- Captured, revocable plugin identities gate all handle operations; no operational call walks a stack.
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
- Platform-specific wrappers maintain classloader-to-identity maps from lifecycle events and invalidate
  identities (and their handles) before a plugin disables or reloads.

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

Redis Pub/Sub adds a durable logical subscription layer above each physical listener. The logical registry retains
the destination, message type, handler registrations, stable identity and admission permit while listener attempts
move through `CONNECTING`, `ACTIVE`, `RECONNECTING`, `CLOSING`, `CLOSED` and `FAILED`. A failed listener is
replaced with bounded exponential backoff and jitter. Per-attempt generations fence stale callbacks and cleanup,
so an old listener cannot remove or overwrite its replacement. Pool recreation reuses the same scoped messaging
view and original `Subscription` handle. Diagnostics expose state, reconnect count, last failure and downtime.

The status command consumes cached snapshots and requests only stale refreshes; it never performs
remote I/O on a platform thread. Snapshot diagnostics include lifecycle, health, circuit, probe time,
failure/recovery counts, backoff, and next recovery attempt. Endpoint migration remains outside this layer.

## Messaging Delivery Guarantee

Redis Pub/Sub remains **at-most-once**. Automatic resubscription restores future delivery, but messages published
while the listener is disconnected may be lost and are not replayed. `MessagingDatabaseProvider.getDurableDataAccess()`
uses Redis Streams for events that must never disappear, including votes, purchases, punishments and cross-server state
changes. It has producer event-ID deduplication, named consumer groups, explicit acknowledgement, pending-entry reclaim,
bounded retries and per-group dead-letter streams. Durable delivery is at-least-once; consumers obtain a processing key
and must commit it under a unique constraint with the business effect before acknowledgement, which provides exactly-once
business effects across redelivery.

The Redis Streams implementation requires Redis 6.2 or newer because it uses `XAUTOCLAIM` for pending-message reclaim.

Redis must itself be configured durably in production (for example AOF with an appropriate fsync policy or RDB plus
durable storage). Streams cannot preserve an event across a Redis data-loss configuration or an ephemeral Redis volume.
