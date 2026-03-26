# Configuration Guide

DataProvider writes defaults on first startup inside the plugin data folder.

## File Layout

- `config.yml`: global backend toggles + ORM settings
- `databases/mysql.yml`
- `databases/mongodb.yml`
- `databases/redis.yml`
- `databases/redis_messaging.yml`

Each backend file supports named sections (`default`, `analytics`, etc.).
Use the same identifier in code when calling `registerDatabase*`.

## Global Keys (`config.yml`)

- `orm.schema_mode`: Hibernate schema mode (for example `validate`, `update`)
- `databases.mysql.enabled`
- `databases.mongodb.enabled`
- `databases.redis.enabled`
- `databases.redis_messaging.enabled`

Example:

```yaml
orm:
  schema_mode: validate
databases:
  mysql:
    enabled: true
  mongodb:
    enabled: false
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
- `queue_capacity`
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

## MongoDB Keys (`databases/mongodb.yml`)

- `host`, `port`, `database`, `username`, `password`
- `authSource` (note exact casing)
- `require_secure_transport`
- `tls.enabled`
- `tls.allow_invalid_hostnames` (must remain `false`; startup fails otherwise)
- `tls.trust_all_certificates` (must remain `false`; startup fails otherwise)
- `tls.trust_store_path` (optional JKS/PKCS12 path for private CA/self-managed trust)
- `tls.trust_store_password` (optional trust store password)
- `tls.trust_store_type` (optional, defaults to JVM `KeyStore.getDefaultType()`)
- `pool_size`
- `queue_capacity`
- `max_connection_pool_size`
- `min_connection_pool_size`
- `connect_timeout_ms`
- `socket_timeout_ms`
- `server_selection_timeout_ms`

## Redis Keys (`databases/redis.yml`)

- `host`, `port`, `user`, `password`, `database`
- `require_secure_transport`
- `tls.enabled`
- `tls.verify_hostname` (must remain `true`; startup fails otherwise)
- `tls.trust_all_certificates` (must remain `false`; startup fails otherwise)
- `tls.trust_store_path` (optional JKS/PKCS12 path for private CA/self-managed trust)
- `tls.trust_store_password` (optional trust store password)
- `tls.trust_store_type` (optional, defaults to JVM `KeyStore.getDefaultType()`)
- `pool.connections`
- `pool.threads`
- `pool.min_idle`
- `pool.max_idle`
- `pool.test_on_borrow`
- `pool.test_while_idle`
- `pool.queue_capacity`
- `connection_timeout_ms`
- `socket_timeout_ms`
- `scan_count`
- `security.max_scan_results`

## Redis Messaging Keys (`databases/redis_messaging.yml`)

- Same network + TLS fields as Redis key-value
- `pool.connections`
- `pool.threads`
- `pool.min_idle`
- `pool.max_idle`
- `pool.test_on_borrow`
- `pool.test_while_idle`
- `pool.queue_capacity`
- `pool.max_subscriptions`
- `connection_timeout_ms`
- `socket_timeout_ms`
- `security.max_payload_chars`
- `security.max_queued_messages_per_handler` (per-subscriber queue cap to isolate slow handlers)

## Common Mistakes

- Identifier mismatch between code and config section names
- Enabling TLS flags without server-side TLS support
- Setting insecure TLS flags (`trust_all_certificates`, `allow_invalid_hostnames`, or `verify_hostname=false`) which now fail startup in 2.0
- Using `queue_capacity` at the root of `redis.yml` instead of `pool.queue_capacity`

## Operational Notes

- Use `default` for single-backend setups.
- Use explicit identifiers (for example `rw`, `ro`, `analytics`) for multi-backend setups.
- Validate trust store configuration in staging before production rollout.
- Never commit production credentials.
- During plugin shutdown across many classes/scopes, pair cleanup with `unregisterAllDatabasesForPlugin()`.
