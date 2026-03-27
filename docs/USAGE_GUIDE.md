# Usage Guide

## 1. Get the API

Velocity:

```java
DataProviderAPI api = proxyServer.getPluginManager()
        .getPlugin("dataprovider")
        .flatMap(container -> container.getInstance()
                .filter(DataProviderApiSupplier.class::isInstance)
                .map(DataProviderApiSupplier.class::cast)
                .map(DataProviderApiSupplier::dataProviderApi))
        .orElseThrow(() -> new IllegalStateException("DataProvider is unavailable."));
```

Bukkit/Paper:

```java
RegisteredServiceProvider<DataProviderAPI> registration =
        Bukkit.getServicesManager().getRegistration(DataProviderAPI.class);
if (registration == null) {
    throw new IllegalStateException("DataProvider is unavailable.");
}
DataProviderAPI api = registration.getProvider();
```

Caller identity is resolved automatically from the plugin runtime context.

## 1.1 API lifecycle across reloads

Treat `DataProviderAPI` as runtime-scoped, not permanent.

- Acquire the API during your plugin enable/start phase.
- Do not keep API references across plugin reloads or disable/enable cycles.
- After DataProvider shuts down, old API handles throw `IllegalStateException`; reacquire a fresh API after DataProvider is enabled again.

## 2. Register a connection

Basic:

```java
DatabaseProvider provider = api.registerDatabase(DatabaseType.MYSQL, "example");
if (provider == null || !provider.isConnected()) {
    // handle unavailable database
}
```

Optional style:

```java
Optional<DatabaseProvider> provider = api.registerDatabaseOptional(DatabaseType.MYSQL, "example");
```

Typed provider style:

```java
Optional<RelationalDatabaseProvider> relational = api.registerDatabaseAs(
        DatabaseType.MYSQL,
        "example",
        RelationalDatabaseProvider.class
);
```

Typed data access style:

```java
Optional<MessagingDataAccess> redisBus = api.registerDataAccess(
        DatabaseType.REDIS_MESSAGING,
        "default",
        MessagingDataAccess.class
);
```

Identifier guidance:

- Prefer `default` for single-connection setups.
- Use explicit names like `example` for relational read/write paths.

## 3. Use the provider safely

`DatabaseProvider` is a read-only handle. Connection lifecycle stays owned by `DataProviderAPI`,
so acquire and release connections through `registerDatabase*` / `unregisterDatabase*`.

`DatabaseProvider` has helper methods to avoid raw casts:

```java
Optional<MessagingDataAccess> bus = provider.getDataAccessOptional(MessagingDataAccess.class);
Optional<DataSource> dataSource = provider.getDataSourceOptional();
```

## 4. Release connections

Release a specific connection:

```java
api.unregisterDatabase(DatabaseType.MYSQL, "example");
```

Release all connections for your default plugin/software scope:

```java
api.unregisterAllDatabases();
```

For full plugin/software shutdown when registrations may come from multiple classes/scopes:

```java
api.unregisterAllDatabasesForPlugin();
```

Optional advanced scoped ownership is documented in `docs/SCOPED_LIFECYCLE.md`.

## 5. ORM usage

For relational providers:

```java
ORMContext orm = new ORMContext(
        "my-plugin",
        dataSource,
        loggerAdapter,
        "validate",
        PlayerEntity.class
);
```
