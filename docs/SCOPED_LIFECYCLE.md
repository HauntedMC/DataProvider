# Scoped Lifecycle (Optional)

`DataProviderScope` is an advanced ownership option.
Use it only when one plugin/software process contains multiple independently managed components.

## Create a Scope

```java
DataProviderScope chatScope = api.scope("component.chat");
```

You can also use a typed scope object:

```java
OwnerScope chatOwner = OwnerScope.of("component.chat");
DataProviderScope chatScope = api.scope(chatOwner);
```

Scope names must be stable, non-blank, and use safe identifier characters.

## Register Through the Scope

```java
Optional<MessagingDataAccess> bus = chatScope.registerDataAccess(
        DatabaseType.REDIS_MESSAGING,
        "hauntedmc",
        MessagingDataAccess.class
);
```

## Release Only That Scope

```java
chatScope.unregisterAllDatabases();
```

`DataProviderScope` is `AutoCloseable`, so it can also be used with try-with-resources:

```java
try (DataProviderScope tempScope = api.scope("component.temp")) {
    tempScope.registerDatabase(DatabaseType.MYSQL, "default");
}
```

## Full Plugin/Process Shutdown

Scope cleanup is targeted.  
For deterministic full shutdown across all scopes, call:

```java
api.unregisterAllDatabasesForPlugin();
```
