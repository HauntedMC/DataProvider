# Distributed coordination

DataProvider exposes a small, platform-neutral coordination API for consumers that need atomic ownership, fencing, compare-and-set, or indexed TTL state on Redis.

The public entry point is `KeyValueDatabaseProvider#getCoordinationDataAccess()`. Coordination is generic infrastructure: DataProvider does not define application leaders, replica groups, failover policy, or feature placement.

## Lease model

A `FencedLease` contains:

- `resource`: the logical resource being coordinated;
- `owner`: the exact process incarnation that currently owns it;
- `fencingToken`: a positive, monotonically increasing ownership generation for that resource;
- `expiresAt`: the expiry calculated from Redis' authoritative server clock.

Use process-incarnation owners rather than a reusable logical service name. A recommended shape is:

```text
<logical-node-id>/<unique-process-id>
```

For example:

```text
worker-01/93286f21-54e8-45dd-a24b-20b4fb98ef94
```

A restart of `worker-01` must use a new process ID. This prevents a restarted process from being mistaken for the JVM/process that acquired an older lease.

## Operations

### `acquire(resource, owner, ttl)`

Acquires only an unowned or expired resource. A live owner is never displaced. Successful acquisitions issue a new fencing token.

Use `acquire` for normal static ownership or leader eligibility where an existing live owner must win.

### `renew(lease, ttl)`

Renews only the exact resource/owner/fencing-token tuple. Renewal extends the lease but keeps the same fencing token because ownership has not changed.

A failed or empty renewal means the caller can no longer prove that lease is current. Application code must not infer authority from a locally cached `FencedLease` after renewal fails.

### `release(lease)`

Releases only the exact current resource/owner/fencing-token tuple. A stale owner or stale token cannot release a newer owner's lease.

### `claim(resource, owner, ttl)`

Performs an explicit latest-owner-wins takeover and always issues a newer fencing token. The former owner is immediately stale even if it still has a locally cached lease object.

`claim` is stronger than normal acquisition. Consumers should reserve it for workflows where forced takeover is intentional rather than using it as a retry mechanism for `acquire`.

## Fencing tokens

Every successful new acquisition or claim for one resource advances that resource's fencing generation. Renewals do not.

A typical sequence is:

```text
process A acquires -> token 41
process A renews  -> token 41
process A releases
process B acquires -> token 42
```

Consumers that protect state outside Redis should persist/compare the fencing token at their own write boundary. A lower token must never be allowed to overwrite work already accepted under a higher token.

## Fenced Redis values

`writeFenced`, `deleteFenced`, `writeFencedIndexed`, and `deleteFencedIndexed` first verify the exact current lease owner and fencing token atomically in Redis.

After another owner has acquired or claimed a newer generation, an old `FencedLease` cannot mutate those fenced values.

Indexed variants additionally maintain an explicit coordination index. `readIndexedValues` returns live values and prunes members whose TTL value has expired.

## Compare-and-set operations

`compareAndSetWithTtl` atomically writes only when the current value matches the expected value. Passing a null expected value means the key must be absent.

`compareAndDelete` deletes only when the current value exactly matches the supplied expected value.

These operations are independent from lease ownership and are useful for small atomic state transitions that do not require a fencing generation.

## Time and expiry

Lease scripts use Redis `TIME`; the Redis server clock is authoritative for the returned `expiresAt` value. Clients should not manufacture lease expiry timestamps locally.

A cached lease does not guarantee current ownership. Network failure, process suspension, lease expiry, or an explicit `claim` may make it stale. Consumers that depend on continuing authority should renew well before expiry and define a local safety margin for stopping authoritative work when renewal can no longer be proven.

## Connection interruption

Redis reconnect does not revive an expired lease. The lease key may expire while a client is disconnected, while the fencing counter remains. A subsequent acquisition receives a newer token and the former lease remains stale.

DataProvider reports coordination operation success or failure; availability policy belongs to the consumer. For example, an application may continue ordinary read-only/local behavior while disabling singleton work whenever lease renewal cannot be proven.

## Scoped ownership

Long-lived subsystems should acquire their backend through a dedicated `DataProviderScope`:

```java
DataProviderScope scope = api.scope("component.coordination");
KeyValueDatabaseProvider redis = scope.registerDatabaseOrThrow(
        DatabaseType.REDIS,
        "coordination",
        KeyValueDatabaseProvider.class
);
CoordinationDataAccess coordination = redis.getCoordinationDataAccess();
```

Closing the scope releases that subsystem's DataProvider registrations without affecting unrelated scopes or plugins.

## What DataProvider deliberately does not decide

DataProvider does not define:

- which nodes are eligible to own a resource;
- automatic or static leader election policy;
- replica-group membership;
- application configuration replication;
- application fail-open/fail-closed behavior;
- application-specific cluster tables.

Those policies belong to the consuming application or framework. DataProvider provides the generic atomic primitives they can build on.
