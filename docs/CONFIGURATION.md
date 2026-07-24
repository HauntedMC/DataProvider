# Configuration Guide

DataProvider writes defaults on first startup inside the plugin data folder.

## File Layout

- `config.yml`: global backend toggles, ORM settings and shared execution limits
- `databases/mysql.yml`
- `databases/mongodb.yml`
- `databases/redis.yml`
- `databases/redis_messaging.yml`

Each backend file supports named sections (`default`, `analytics`, etc.). Use the same identifier in code when calling `registerDatabase*`.

## Reloading Configuration

`/dataprovider reload` loads and validates `config.yml` and every file in `databases/` as one snapshot. If any file is missing, malformed or invalid, the reload is rejected and the active configuration remains unchanged.

Existing database connections retain their current client and pool settings until recovery requires a local rebuild. Shared execution lanes are runtime-owned and are created once during DataProvider startup; changes below `execution` require a DataProvider/server restart. Changes below `resilience` take effect immediately after a successful reload.

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

Scheduling is fair between plugins first and between each plugin's connections second, but is work-conserving: an idle lane is available to whichever plugin has work. A named backend resource owns one physical pool/client and is shared by its plugin leases.

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
- `connection_timeout_ms`
- `socket_timeout_ms`
- `security.max_payload_chars`
- `security.max_queued_messages_per_handler`

Each active Redis subscription owns a long-lived physical connection. DataProvider adds subscription capacity on top of `pool.connections`, so subscriptions cannot consume command connections reserved for publishing and shutdown.

## Operational Notes

- Capacity rejection completes the returned future exceptionally; it does not silently drop database work.
- Rejections retain a stable reason such as lane queue full, plugin queue limit, connection queue limit, closed scope or runtime shutdown.
- Closing a connection rejects queued work, waits for active work up to the configured grace period, then completes remaining futures exceptionally and interrupts the worker.
- Shared workers clear interrupt state before serving another plugin.
- Messaging handler queues drop excess messages rather than allowing unbounded growth; drop counts are included in execution metrics.
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
