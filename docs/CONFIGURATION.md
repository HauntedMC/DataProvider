# Configuration Guide

DataProvider writes defaults on first startup inside the plugin data folder.

## File Layout

- `config.yml`: global backend toggles, ORM settings and shared execution limits
- `databases/mysql.yml`
- `databases/mongodb.yml`
- `databases/redis.yml`
- `databases/redis_messaging.yml`

Each backend file supports named sections (`analytics`, `primary`, etc.). Use the same identifier in code when calling `registerDatabase*`. Every connection section must declare an explicit access policy; there is no wildcard access.

`default` is a bundled example, not a connection name for production use. On every DataProvider startup it is replaced wholesale with the version shipped by the current plugin, so its examples, comments, and safe defaults stay current. Put real connections in a differently named section; those user-owned sections are never changed by the upgrade process.

## Connection Access Policy

Put an `access` block in every named connection. `owner_plugin` is required and must be the exact Paper plugin name or Velocity plugin id. `shared_with` is an optional, explicit allowlist. DataProvider verifies every configured name against the platform plugin manager when the connection is first requested, before copying credentials or creating a backend client.

```yaml
network-data:
  access:
    owner_plugin: ServerFeatures
    shared_with:
      - Economy
      - Moderation
  host: localhost
  # remaining backend settings...
```

Only `ServerFeatures`, `Economy`, and `Moderation` can register `network-data`. The owner is the administrative steward and resource-key anchor; it has no greater database permissions than a plugin in `shared_with`. A non-empty `shared_with` is also the explicit opt-in to share one physical pool/client across those plugin leases. A connection with `shared_with: []` is private and its physical resource key includes the owner plugin. Connections with the same identifier never share merely because their names match.

### Trust boundary

`owner_plugin` and `shared_with` protect against configuration mistakes and misuse by cooperative plugins; they are not a sandbox or a security boundary against malicious installed code. Every plugin in one Paper or Velocity process shares the JVM and operating-system account. A hostile plugin can bypass classloader-derived checks by reading DataProvider files, connecting to a database directly, using reflection, or invoking an authorized plugin as a confused deputy. Install only trusted, reviewed plugins in the same process.

DataProvider binds returned providers, data-access objects, schema managers, subscriptions, and JDBC data sources/objects to the plugin identity that obtained them. Each public operation revalidates that identity, preventing ordinary accidental handle transfer between plugins. This is defence against cooperative-plugin mistakes only, not a malicious-code mitigation.

If you need to support genuinely untrusted modules, keep database credentials and access in a separate service or sidecar process and use authenticated RPC. JVM classloader checks cannot provide that isolation.

The generated `default` sections intentionally start with a blank owner and are unusable. This prevents a new installation or upgrade from accidentally exposing default credentials. Copy it to a new named section, then set that section's plugin ids and credentials before the relevant plugin registers the connection.

## Configuration Upgrades

At startup, `config.yml` is reconciled with the bundled schema: newly introduced keys are added with their current defaults, administrator-set values are retained, and keys no longer supported by the current release are removed. The file is written through a sibling temporary file before it replaces the live file.

Database files receive the narrower policy above: only their `default` section is refreshed. All named connection sections, including their unknown extension keys and credentials, are left intact.

## Reloading Configuration

`/dataprovider reload` loads and validates `config.yml` and every file in `databases/` as one snapshot. If any file is missing, malformed or invalid, the reload is rejected and the active configuration remains unchanged.

For every active named connection whose endpoint, authentication, TLS, pool, or other backend-client setting changed, reload first constructs and validates a new physical client/pool from the candidate snapshot. It then swaps that validated generation beneath the existing logical leases and retires the old client. Existing API/provider references remain valid, and Redis messaging subscription intent is reattached to the new generation. If a replacement cannot connect, the reload is rejected and the active configuration and clients remain unchanged. Access-policy-only changes do not rebuild a backend client; a plugin whose access is removed (or made invalid) has its active lease closed immediately. Shared execution lanes are runtime-owned and are created once during DataProvider startup; changes below `execution` require a DataProvider/server restart. Changes below `resilience` take effect immediately after a successful reload.

## Global Keys (`config.yml`)

- `orm.schema_mode`: Hibernate schema mode, such as `validate` or `update`
- `databases.<type>.enabled`: enable or disable each backend type
- `execution.scope_shutdown_grace_ms`: time allowed for active work in one closing connection scope
- `execution.runtime_shutdown_grace_ms`: total graceful shutdown window for shared execution lanes
- `execution.messaging_subscriptions.global`
- `execution.messaging_subscriptions.per_plugin`
- `execution.messaging_subscriptions.per_connection`

Each execution lane (`relational`, `document`, `redis`, `messaging`) supports:

- `workers`: shared worker count for the lane
- `queue_capacity`: total queued-task limit for the lane
- `per_plugin_queue`: maximum queued tasks for one plugin
- `per_resource_queue`: maximum queued tasks for one named backend resource, shared by its plugin leases

Scheduling is fair between plugins first and between each plugin's connections second, but is work-conserving: an idle lane is available to whichever plugin has work. A named backend resource owns one physical pool/client only when its access policy explicitly lists one or more shared plugins.

Example:

```yaml
orm:
  schema_mode: validate

execution:
  scope_shutdown_grace_ms: 2000
  runtime_shutdown_grace_ms: 5000
  messaging_subscriptions:
    global: 256
    per_plugin: 64
    per_connection: 32
  lanes:
    relational:
      workers: 8
      queue_capacity: 2048
      per_plugin_queue: 512
      per_resource_queue: 128

    document:
      workers: 8
      queue_capacity: 2048
      per_plugin_queue: 512
      per_resource_queue: 128

    redis:
      workers: 8
      queue_capacity: 2048
      per_plugin_queue: 512
      per_resource_queue: 128

    messaging:
      workers: 8
      queue_capacity: 4096
      per_plugin_queue: 1024
      per_resource_queue: 256

resilience:
  workers: 2
  queue_capacity: 128
  health_interval_ms: 15000
  stale_threshold_ms: 45000
  failure_threshold: 3
  recovery_threshold: 1
  initial_backoff_ms: 1000
  max_backoff_ms: 30000
  jitter: 0.20
  shutdown_grace_ms: 2000

databases:
  mysql:
    enabled: true
  mongodb:
    enabled: true
  redis:
    enabled: true
  redis_messaging:
    enabled: true
```

## MySQL Keys (`databases/mysql.yml`)

- `host`, `port`, `database`, `username`, `password`
- `ssl_mode`
- `require_secure_transport`
- `allow_public_key_retrieval`
- `pool_size`
- `min_idle`
- `connection_timeout_ms`
- `validation_timeout_ms`
- `idle_timeout_ms`
- `max_lifetime_ms`
- `leak_detection_threshold_ms`
- `connect_timeout_ms`
- `socket_timeout_ms`
- `query_timeout_seconds`
- `default_fetch_size`
- `cache_prepared_statements`
- `prepared_statement_cache_size`
- `prepared_statement_cache_sql_limit`

`pool_size` controls physical Hikari connections only. Asynchronous worker and queue capacity comes from the relational execution lane.

## MongoDB Keys (`databases/mongodb.yml`)

- `host`, `port`, `database`, `username`, `password`
- `authSource` (exact casing)
- `require_secure_transport`
- `tls.enabled`
- `tls.allow_invalid_hostnames` (must remain `false`)
- `tls.trust_all_certificates` (must remain `false`)
- `tls.trust_store_path`
- `tls.trust_store_password`
- `tls.trust_store_type`
- `max_connection_pool_size`
- `min_connection_pool_size`
- `connect_timeout_ms`
- `socket_timeout_ms`
- `server_selection_timeout_ms`

MongoDB client-pool settings remain connection-specific. Worker and queue capacity comes from the document execution lane.

## Redis Keys (`databases/redis.yml`)

- `host`, `port`, `user`, `password`, `database`
- `require_secure_transport`
- `tls.enabled`
- `tls.verify_hostname` (must remain `true`)
- `tls.trust_all_certificates` (must remain `false`)
- `tls.trust_store_path`
- `tls.trust_store_password`
- `tls.trust_store_type`
- `pool.connections`
- `pool.min_idle`
- `pool.max_idle`
- `pool.test_on_borrow`
- `pool.test_while_idle`
- `connection_timeout_ms`
- `socket_timeout_ms`
- `scan_count`
- `security.max_scan_results`

Jedis connection-pool settings remain connection-specific. Worker and queue capacity comes from the Redis execution lane.

## Redis Messaging Keys (`databases/redis_messaging.yml`)

- Same network and TLS fields as Redis key-value
- `pool.connections`: command capacity reserved for publish and control operations
- `pool.min_idle`
- `pool.max_idle`
- `pool.test_on_borrow`
- `pool.test_while_idle`
- `pool.max_subscriptions`: local provider subscription cap
- `pool.handler_batch_size`: messages processed before a hot handler yields shared capacity
- `reconnect.initial_backoff_ms`: delay before the first physical listener replacement
- `reconnect.max_backoff_ms`: upper bound for exponential reconnect delay
- `reconnect.jitter`: random proportional variation from `0.0` through `1.0`
- `reconnect.max_attempts`: terminal retry limit; `0` retries until close or scope shutdown
- `connection_timeout_ms`
- `socket_timeout_ms`
- `security.max_payload_chars`
- `security.max_queued_messages_per_handler`
- `durable.batch_size`: maximum stream entries fetched or reclaimed per polling cycle
- `durable.read_block_ms`: bounded blocking read time; also bounds consumer shutdown latency. `socket_timeout_ms` must exceed it by at least 250 ms.
- `durable.reclaim_idle_ms`: idle duration before another named consumer may reclaim pending work; set it higher than the longest normal business transaction to avoid concurrent redelivery of slow work
- `durable.max_attempts`: attempts before an unacknowledged or failed entry is atomically moved to its dead-letter stream
- `durable.retention_ms` / `durable.retention_max_entries`: retention limits; trimming does not pass any group’s pending or undelivered position
- `durable.retention_trim_interval_ms`: minimum interval between safe retention scans; this avoids a costly full group scan after every acknowledgement
- `durable.deduplication_ttl_seconds`: producer event-ID deduplication lifetime; it must be at least `retention_ms` rounded up to seconds
- `durable.dead_letter_max_entries`: bounded per-group dead-letter stream length

Use a consumer name that is unique among simultaneous process instances in a consumer group. Redis may reclaim a delivery whose idle time exceeds `durable.reclaim_idle_ms`, so the business operation must remain idempotent even when a slow or failed process is still running. An inactive group prevents trimming past its undelivered position; intentionally retired groups should be drained and removed with an operational Redis procedure.
Redis 6.2 or newer is required. Per-group dead letters are stored in `<stream>:durable:dead:<group>` and retain the source entry ID, event ID, processing key, payload, attempt and failure reason.

Each logical Redis subscription owns at most one long-lived physical listener connection. DataProvider adds subscription capacity on top of `pool.connections`, so subscriptions cannot consume command connections reserved for publishing and shutdown. Listener failures retain the original logical subscription and handler registrations, then reconnect with bounded exponential backoff and jitter. Pool recreation by the resilience layer does not replace the logical subscription handle.

## Operational Notes

- Capacity rejection completes the returned future exceptionally; it does not silently drop database work.
- Rejections retain a stable reason such as lane queue full, plugin queue limit, connection queue limit, closed scope or runtime shutdown.
- Closing a connection rejects queued work, waits for active work up to the configured grace period, then completes remaining futures exceptionally and interrupts the worker.
- Shared workers clear interrupt state before serving another plugin.
- Messaging handler queues drop excess messages rather than allowing unbounded growth; drop counts are included in execution metrics.
- Redis Pub/Sub delivery is at-most-once. Messages published while a listener is disconnected can be lost and are not replayed.
- Use `MessagingDatabaseProvider.getDurableDataAccess()` for votes, purchases, punishments and cross-server state changes. A consumer must persist `DurableEvent.processingKey()` under a unique constraint in the same business transaction, then call `delivery.acknowledge()` after commit.
- Use `default` for single-backend setups and explicit identifiers such as `rw`, `ro` or `analytics` for multi-backend setups.
- Never commit production credentials.
- During full plugin shutdown, pair cleanup with `unregisterAllDatabasesForPlugin()`.

## Resilience Runtime

`config.yml` has a `resilience` section for core-owned health and recovery work. `workers` and
`queue_capacity` bound remote probes; `health_interval_ms` and `stale_threshold_ms` control cached
status freshness; `failure_threshold`/`recovery_threshold` control circuit transitions; and
`initial_backoff_ms`, `max_backoff_ms`, and `jitter` control recovery pacing. Invalid values reject a
reload atomically. Reloaded settings apply to future probes without changing endpoint credentials or
replacing consumer handles.

When repeated probes open a circuit, new operations fail immediately with `BACKEND_UNAVAILABLE` and
`ExecutionOutcome.NOT_STARTED`. DataProvider never queues, retries, or replays application work.
