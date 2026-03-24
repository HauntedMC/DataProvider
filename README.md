# DataProvider

DataProvider is a plugin-scoped database access layer for Velocity and Bukkit/Paper plugins.

It provides:
- Caller-verified API access per plugin.
- Unified registration for `MYSQL`, `MONGODB`, `REDIS`, and `REDIS_MESSAGING`.
- Shared connection lifecycle management with reference counting.
- Optional ORM support through `ORMContext`.

## Why this exists

Most plugin projects repeat the same patterns:
- Load db config files.
- Build clients and pools.
- Handle reconnect/disconnect and cleanup.
- Cast generic providers into specific access interfaces.

DataProvider centralizes that work so feature code stays focused on business logic.

## Quick Start

```java
DataProviderAPI api = VelocityDataProvider.getDataProviderAPI();

Optional<MessagingDataAccess> bus = api.registerDataAccess(
        DatabaseType.REDIS_MESSAGING,
        "default",
        MessagingDataAccess.class
);

bus.ifPresent(redisBus -> {
    redisBus.subscribe("proxy.staffchat.message", StaffChatMessage.class, msg -> {
        // Handle incoming message
    });
});
```

## Docs

- [Usage guide](docs/USAGE_GUIDE.md)
- [Best practices](docs/BEST_PRACTICES.md)
- [Architecture and maintainability review](docs/ARCHITECTURE_REVIEW.md)
- [ProxyFeatures integration notes](docs/PROXYFEATURES_INTEGRATION.md)
- [Examples](docs/examples)

## Notes

- Caller identity is resolved at runtime from the platform plugin context.
- `registerDatabase(...)` is reference-counted internally; repeated registrations reuse the same connection.
