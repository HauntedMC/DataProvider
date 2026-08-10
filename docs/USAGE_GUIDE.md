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
        .orElseThrow(() -> new IllegalStateException("DataProvider is unavailable."))
        .forPlugin(this);
```

Bukkit/Paper:

```java
RegisteredServiceProvider<DataProviderAPI> registration =
        Bukkit.getServicesManager().getRegistration(DataProviderAPI.class);
if (registration == null) {
    throw new IllegalStateException("DataProvider is unavailable.");
}
DataProviderAPI api = registration.getProvider().forPlugin(this);
```

Bind once from your plugin's enable/initialization callback, then retain that bound facade for
asynchronous work, futures, callbacks, and shared-library calls. DataProvider does not inspect
those call stacks or query the platform plugin manager during database operations.

## 1.1 API lifecycle across reloads

Treat `DataProviderAPI` as runtime-scoped, not permanent.

- Acquire the API during your plugin enable/start phase.
- Do not keep API references across plugin reloads or disable/enable cycles.
- After DataProvider shuts down, API operations throw `ProviderClosedException`; reacquire a fresh API after DataProvider is enabled again.

## 1.2 Built-in admin commands

`DataProvider` ships with runtime diagnostics commands for Bukkit/Paper and Velocity. The root is `/dataprovider` on
Paper and `/dataproviderproxy` on Velocity:

- `<root> status [summary|connections] [unhealthy] [plugin <name>] [type <databaseType>]` displays cached remote health and age without blocking the server thread; it starts refreshed probes asynchronously.
- `<root> config`
- `<root> reload`

Permission nodes:

- `dataprovider.command.status`
- `dataprovider.command.config`
- `dataprovider.command.reload`

Players without any of these permissions do not receive the platform-specific command root in their Paper or Velocity command tree.

## 2. Register a connection

Basic:

```java
RelationalDatabaseProvider provider = (RelationalDatabaseProvider) api.registerDatabaseOrThrow(
        DatabaseType.MYSQL, "example"
);
```

Identifier guidance:

- Prefer `default` for single-connection setups.
- Use explicit names like `example` for relational read/write paths.

## 3. Use the provider safely

`DatabaseProvider` is a read-only handle. Connection lifecycle stays owned by `DataProviderAPI`,
so acquire and release connections through `registerDatabase*` / `unregisterDatabase*`.

Cast the strict registration result to the provider interface for the requested backend:

```java
MessagingDatabaseProvider provider = (MessagingDatabaseProvider) api.registerDatabaseOrThrow(
        DatabaseType.REDIS_MESSAGING, "example"
);
MessagingDataAccess bus = provider.getDataAccess();
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
ORMContext orm = api.createOrmContext(
        dataSource,
        loggerAdapter,
        "validate",
        PlayerEntity.class
);
```

Pass the `DataSource` returned by the registered relational provider. DataProvider rejects unmanaged data sources here so ORM connection acquisition stays within the same bounded resource admission policy as JDBC and `RelationalDataAccess`.
The ORM logging identity is always derived from the calling plugin; it cannot be supplied by the consumer.

`ORMContext` is part of the public API. Add only `dataprovider-api` as `compileOnly`, import
`nl.hauntedmc.dataprovider.api.orm.ORMContext`, and create contexts with
`DataProviderAPI.createOrmContext(...)`.
