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

- `<root> help` lists the available views and permissions.
- `<root> status [summary]` displays cached connection, health, consumer, backend, and ORM state without blocking the server thread.
- `<root> diagnostics` adds lifecycle, circuit, failure, reconnect, and probe-age detail for each connection.
- `<root> connections [unhealthy|plugin <name>|type <databaseType>]` filters registered logical connections.
- `<root> health [check]` displays connections requiring attention; `check` runs remote probes asynchronously and sends the refreshed result afterwards.
- `<root> config` displays current runtime configuration state.
- `<root> reload` validates and atomically reloads the configuration.

Permission nodes:

- `dataprovider.command.status`
- `dataprovider.command.config`
- `dataprovider.command.reload`

Players without any of these permissions do not receive the platform-specific command root in their Paper or Velocity command tree.

## 2. Register a connection

The generated `default` backend section is a template and is replaced on startup. Copy it to a named section such as `example` or `main`, configure that section, and use the same identifier in code.

Use the typed overload when you need a backend-specific provider:

```java
RelationalDatabaseProvider provider = api.registerDatabaseOrThrow(
        DatabaseType.MYSQL,
        "example",
        RelationalDatabaseProvider.class
);
```

The original two-argument method remains available when generic `DatabaseProvider` access is sufficient.

For multiple connections, use explicit purpose-oriented identifiers such as `rw`, `ro`, or `analytics` and keep them aligned with the configuration sections.

## 3. Use the provider safely

`DatabaseProvider` is a read-only handle. Connection lifecycle stays owned by `DataProviderAPI`,
so acquire and release connections through `registerDatabaseOrThrow` / `unregisterDatabase`.

Backend-specific providers expose their typed data-access contract directly:

```java
MessagingDatabaseProvider provider = api.registerDatabaseOrThrow(
        DatabaseType.REDIS_MESSAGING,
        "example",
        MessagingDatabaseProvider.class
);
MessagingDataAccess bus = provider.getDataAccess();
```

Generic code can check `DatabaseProvider.supportsDataSource()` before requesting a JDBC `DataSource`.
Relational providers return `true`; non-relational providers retain the existing
`UnsupportedOperationException` behavior from `getDataSource()`.

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
ORMContext orm = api.createConfiguredOrmContext(
        provider.getDataSource(),
        loggerAdapter,
        PlayerEntity.class
);
```

`createConfiguredOrmContext(...)` uses the administrator-configured `orm.schema_mode`. The older
`createOrmContext(...)` signature with a schema-mode string remains for compatibility, but the
DataProvider runtime ignores that argument.

Pass the `DataSource` returned by the registered relational provider. DataProvider rejects unmanaged data sources here so ORM connection acquisition stays within the same bounded resource admission policy as JDBC and `RelationalDataAccess`.
The ORM logging identity is always derived from the calling plugin; it cannot be supplied by the consumer.

`ORMContext` is part of the public API. Add only `dataprovider-api` as `compileOnly`, import
`nl.hauntedmc.dataprovider.api.orm.ORMContext`, and create new contexts with
`DataProviderAPI.createConfiguredOrmContext(...)`.
