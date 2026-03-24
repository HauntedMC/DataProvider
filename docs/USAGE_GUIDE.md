# Usage Guide

## 1. Get the API

Velocity:

```java
DataProviderAPI api = VelocityDataProvider.getDataProviderAPI();
```

Bukkit/Paper:

```java
DataProviderAPI api = BukkitDataProvider.getDataProviderAPI();
```

Caller identity is resolved automatically from the plugin runtime context.

## 2. Register a connection

Basic:

```java
DatabaseProvider provider = api.registerDatabase(DatabaseType.MYSQL, "player_data_rw");
if (provider == null || !provider.isConnected()) {
    // handle unavailable database
}
```

Optional style:

```java
Optional<DatabaseProvider> provider = api.registerDatabaseOptional(DatabaseType.MYSQL, "player_data_rw");
```

Typed provider style:

```java
Optional<RelationalDatabaseProvider> relational = api.registerDatabaseAs(
        DatabaseType.MYSQL,
        "player_data_rw",
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

## 3. Use the provider safely

`DatabaseProvider` has helper methods to avoid raw casts:

```java
Optional<MessagingDataAccess> bus = provider.getDataAccessOptional(MessagingDataAccess.class);
Optional<DataSource> dataSource = provider.getDataSourceOptional();
```

## 4. Release connections

Release a specific connection:

```java
api.unregisterDatabase(DatabaseType.MYSQL, "player_data_rw");
```

Release all connections for your plugin context:

```java
api.unregisterAllDatabases();
```

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
