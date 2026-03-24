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
- `queue_capacity`

## MongoDB Keys (`databases/mongodb.yml`)

- `host`, `port`, `database`, `username`, `password`
- `authSource` (note exact casing)
- `require_secure_transport`
- `tls.enabled`
- `tls.allow_invalid_hostnames`
- `tls.trust_all_certificates`
- `pool_size`
- `queue_capacity`

## Redis Keys (`databases/redis.yml`)

- `host`, `port`, `user`, `password`, `database`
- `require_secure_transport`
- `tls.enabled`
- `tls.verify_hostname`
- `tls.trust_all_certificates`
- `pool.connections`
- `pool.threads`
- `queue_capacity`

## Redis Messaging Keys (`databases/redis_messaging.yml`)

- Same network + TLS fields as Redis key-value
- `pool.connections`
- `pool.threads`
- `pool.queue_capacity`
- `pool.max_subscriptions`
- `security.max_payload_chars`

## Common Mistakes

- Identifier mismatch between code and config section names
- Enabling TLS flags without server-side TLS support
- Assuming Redis and Redis Messaging use identical pool key paths (`queue_capacity` differs)

## Operational Notes

- Use `default` for single-backend setups.
- Use explicit identifiers (for example `rw`, `ro`, `analytics`) for multi-backend setups.
- Validate TLS flags in staging before production rollout.
- Never commit production credentials.
