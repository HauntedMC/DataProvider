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

- `dataprovider-api`: public contracts and API-facing dependency types only; it must never depend on core, platforms, or storage drivers.
- `dataprovider-core`: registry/factory/configuration, database drivers, and the Hibernate implementation behind `api.orm`.
- `dataprovider-platform-common`: shared lifecycle, commands, and platform logger adapters.
- `dataprovider-platform-paper` / `dataprovider-platform-velocity`: host adapters and shaded server artifacts.
- Every module keeps tests in its own `src/test/java` tree.

## Coding Guidelines

- Prefer typed APIs (`registerDatabaseAs`, `registerDataAccess`) over manual casting.
- Keep connection registration in startup lifecycle paths, not request hot paths.
- Handle external IO failures as non-fatal where possible and log actionable context.
- Keep platform-specific integration (`platform.bukkit`, `platform.velocity`) thin and isolated.
- Put cross-platform wrapper behavior in `platform.common` before adding platform-local duplication.
- Avoid leaking plugin context across module boundaries.

## Manual Validation Checklist

- Plugin starts cleanly on both Velocity and Bukkit/Paper.
- DB config defaults generate as expected.
- Register/unregister lifecycle leaves no leaked connections.
- Messaging subscriptions are unsubscribed during shutdown.
