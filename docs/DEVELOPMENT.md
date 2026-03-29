# Development Notes

## Local Build Commands

```bash
mvn -q -DskipTests compile
mvn -q test
mvn -B verify
mvn -B -DskipTests checkstyle:check
mvn -B package
```

## Typical Workflow

1. Create a branch from `main`.
2. Make focused changes.
3. Run compile + tests locally.
4. Run `verify` and `checkstyle` before opening a PR.
5. Open a pull request using the repository template.

## Project Structure

- `src/main/java/nl/hauntedmc/dataprovider/api`: public API contracts
- `src/main/java/nl/hauntedmc/dataprovider/internal`: registry/factory/config internals
- `src/main/java/nl/hauntedmc/dataprovider/database`: backend implementations
- `src/main/java/nl/hauntedmc/dataprovider/logging`: backend-agnostic logging contracts + adapters
- `src/main/java/nl/hauntedmc/dataprovider/platform/internal`: shared platform lifecycle + command behavior
- `src/main/java/nl/hauntedmc/dataprovider/platform`: Bukkit/Velocity adapters
- `src/test/java`: unit tests by package

## Coding Guidelines

- Prefer typed APIs (`registerDatabaseAs`, `registerDataAccess`) over manual casting.
- Keep connection registration in startup lifecycle paths, not request hot paths.
- Handle external IO failures as non-fatal where possible and log actionable context.
- Keep platform-specific integration (`platform.bukkit`, `platform.velocity`) thin and isolated.
- Put cross-platform wrapper behavior in `platform.internal` before adding platform-local duplication.
- Avoid leaking plugin context across module boundaries.

## Manual Validation Checklist

- Plugin starts cleanly on both Velocity and Bukkit/Paper.
- DB config defaults generate as expected.
- Register/unregister lifecycle leaves no leaked connections.
- Messaging subscriptions are unsubscribed during shutdown.
