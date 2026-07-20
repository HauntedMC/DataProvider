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

Look up a provider that is owned by the same scope:

```java
Optional<MessagingDataAccess> bus = chatScope.getRegisteredDataAccess(
        DatabaseType.REDIS_MESSAGING,
        "hauntedmc",
        MessagingDataAccess.class
);
```

Scoped lookups do not expose a connection that is registered only by another owner scope.

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

Closing a DataProvider-provided scope is thread-safe and terminal. Its state transitions from
`OPEN`, through `CLOSING`, to `CLOSED`; closing it more than once is safe. Registration, lookup,
and unregistration operations are rejected after closure, so create a new scope if the component
is started again.

Scopes with the same owner name continue to share ownership for the current release. Closing one
therefore releases all registrations held under that owner name.

## Full Plugin/Process Shutdown

Scope cleanup is targeted.  
For deterministic full shutdown across all scopes, call:

```java
api.unregisterAllDatabasesForPlugin();
```
