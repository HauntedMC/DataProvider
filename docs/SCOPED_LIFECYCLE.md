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
MessagingDatabaseProvider provider = chatScope.registerDatabaseOrThrow(
        DatabaseType.REDIS_MESSAGING,
        "hauntedmc",
        MessagingDatabaseProvider.class
);
MessagingDataAccess bus = provider.getDataAccess();
```

Look up a provider that is owned by the same scope:

```java
MessagingDatabaseProvider provider = chatScope.requireRegisteredDatabase(
        DatabaseType.REDIS_MESSAGING,
        "hauntedmc",
        MessagingDatabaseProvider.class
);
MessagingDataAccess bus = provider.getDataAccess();
```

Scoped lookups do not expose a connection that is registered only by another scope.

## Release Only That Scope

```java
chatScope.unregisterAllDatabases();
```

`DataProviderScope` is `AutoCloseable`, so it can also be used with try-with-resources:

```java
try (DataProviderScope tempScope = api.scope("component.temp")) {
    tempScope.registerDatabaseOrThrow(DatabaseType.MYSQL, "example");
}
```

Closing a DataProvider-provided scope is thread-safe and terminal. Its state transitions from
`OPEN`, through `CLOSING`, to `CLOSED`; closing it more than once is safe. Registration, lookup,
and unregistration operations are rejected after closure, so create a new scope if the component
is started again.

Each `DataProviderScope` instance owns an independent internal registration scope, even when two
scope objects expose the same `OwnerScope` value. Closing one scope therefore releases only that
scope object's registrations.

## Full Plugin/Process Shutdown

Scope cleanup is targeted.  
For deterministic full shutdown across all scopes, call:

```java
api.unregisterAllDatabasesForPlugin();
```
