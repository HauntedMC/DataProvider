# DataProvider

[![CI Tests and Coverage](https://github.com/HauntedMC/DataProvider/actions/workflows/ci-tests-and-coverage.yml/badge.svg?branch=main)](https://github.com/HauntedMC/DataProvider/actions/workflows/ci-tests-and-coverage.yml)
[![CI Lint](https://github.com/HauntedMC/DataProvider/actions/workflows/ci-lint.yml/badge.svg?branch=main)](https://github.com/HauntedMC/DataProvider/actions/workflows/ci-lint.yml)
[![Release](https://img.shields.io/github/v/release/HauntedMC/DataProvider)](https://github.com/HauntedMC/DataProvider/releases)
[![License](https://img.shields.io/github/license/HauntedMC/dataprovider)](LICENSE)
[![Java 25](https://img.shields.io/badge/Java-25-007396)](https://adoptium.net/)

Build plugins and services, not database plumbing.

`DataProvider` is shared infrastructure for plugin developers on Velocity and Bukkit/Paper.  
It gives you one clean API for MySQL, MongoDB, Redis, and Redis messaging so your plugin code can stay focused on gameplay and business logic.

## Why Use DataProvider?

- Faster development: stop rewriting connection, pooling, and lifecycle code in every plugin.
- Consistent developer experience: same registration and access flow across multiple backends.
- Safer multi-plugin setup: caller-scoped access rules prevent cross-plugin misuse.
- Cleaner codebase: typed APIs, Optional read helpers, and capability checks reduce casting and repetitive boilerplate.
- Better runtime behavior: connection reuse and lifecycle cleanup are handled centrally.

## Features

- Following data backends are implemented: `MYSQL`, `MONGODB`, `REDIS`, `REDIS_MESSAGING`
- Platform support: Velocity + Bukkit/Paper
- Optional Hibernate ORM support for relational workflows (`nl.hauntedmc.dataprovider.api.orm.ORMContext`)
- Disposable Pub/Sub plus capability-discoverable durable acknowledged Redis messaging
- Atomic Redis coordination with renewable fenced leases, monotonic fencing tokens, fenced writes/deletes, and compare-and-set operations

## Requirements

- Java 25
- The currently pinned Paper and Velocity runtime builds (see the root `pom.xml`)
- MySQL, MongoDB, and/or Redis for the backends you enable

## Quick Start

Resolve the API from your platform runtime:

Velocity:

```java
DataProviderApiSupplier supplier = proxyServer.getPluginManager()
        .getPlugin("dataprovider")
        .flatMap(container -> container.getInstance()
                .filter(DataProviderApiSupplier.class::isInstance)
                .map(DataProviderApiSupplier.class::cast))
        .orElseThrow(() -> new IllegalStateException("DataProvider is unavailable."));
DataProviderAPI api = supplier.dataProviderApiFor(this); // bind once during initialization
```

Bukkit/Paper:

```java
RegisteredServiceProvider<DataProviderAPI> registration =
        Bukkit.getServicesManager().getRegistration(DataProviderAPI.class);
if (registration == null) {
    return;
}
DataProviderAPI api = registration.getProvider().forPlugin(this); // bind once during onEnable
```

Copy the generated backend `default` template to a named connection such as `example`, configure its access policy and credentials, then register that identifier:

```java
RelationalDatabaseProvider mysql = api.registerDatabaseOrThrow(
        DatabaseType.MYSQL,
        "example",
        RelationalDatabaseProvider.class
);

api.unregisterDatabase(DatabaseType.MYSQL, "example");
```

If you maintain multiple plugins, this gives your team one standard integration model instead of backend-specific code per project.

For distributed ownership/fencing semantics, see [Distributed coordination](docs/COORDINATION.md).

## Admin Commands

Paper uses `/dataprovider` (alias `/dp`); Velocity uses `/dataproviderproxy` (alias `/dp`). Both use native,
permission-aware command trees and offer the same formatted operational views.

- `<root> help` lists only the administrative views the sender can actually use.
- `<root> status [summary]` shows connection, health, consumer, backend, and ORM state from cached data.
- `<root> diagnostics` adds per-connection lifecycle, circuit, failure, reconnect, and probe-age detail.
- `<root> connections [unhealthy|plugin <name>|type <databaseType>|page <number>]` filters or pages through logical database registrations.
- `<root> health` shows connections needing attention; `<root> health check` forces remote health probes asynchronously, then displays the refreshed result.
- `<root> config` prints the current ORM schema mode and backend enablement.
- `<root> reload` validates and atomically reloads `config.yml` plus every `databases/*.yml` file. Existing connections retain their current settings until reconnected.

Permissions:

- `dataprovider.command.status`
- `dataprovider.command.config`
- `dataprovider.command.reload`

The platform-specific command root is only sent to players with at least one of these operational permissions. This keeps the
administrative command out of command completion and discovery for other players on both Paper and Velocity.

Admin-command colours come from the separately versioned `hauntedmc-theme-palette` artifact. Both bundled platform jars
include that palette, so server operators do not need to install another plugin.

## Install DataProvider (Server)

1. Build or download the bundled platform jar for your server:
   `dataprovider-platform-paper-*-bundled.jar` or `dataprovider-platform-velocity-*-bundled.jar`.
2. Put it in your server `plugins/` directory.
3. Start once to generate default configuration.
4. Configure `plugins/DataProvider/config.yml` and `plugins/DataProvider/databases/*.yml`.

## Add It to Your Plugin Project

Use the API artifact in plugins that consume DataProvider. Platform bundles provide it at runtime.

- `dataprovider-api`: public integration contracts, including the ORM context contract and factory; use this for all consumers.
- `dataprovider-core`: internal registry, configuration, storage-driver, and ORM implementation details.
- `dataprovider-platform-paper` / `dataprovider-platform-velocity`: server plugin distributions.

Repository:

- `https://maven.pkg.github.com/HauntedMC/DataProvider`

Maven:

```xml
<repository>
  <id>github</id>
  <url>https://maven.pkg.github.com/HauntedMC/DataProvider</url>
</repository>
```

```xml
<dependency>
  <groupId>nl.hauntedmc.dataprovider</groupId>
  <artifactId>dataprovider-api</artifactId>
  <version>3.4.2</version>
  <scope>provided</scope>
</dependency>
```

Gradle (Groovy):

```groovy
compileOnly "nl.hauntedmc.dataprovider:dataprovider-api:3.4.2"
```

GitHub Packages authentication details are in the docs.

## Build

```bash
./mvnw -q -DskipTests compile
./mvnw -q test
./mvnw -B -ntp -Pintegration-tests verify
./mvnw -B -ntp -Pplatform-acceptance verify
```

The Maven Wrapper pins the build-tool version. The platform-acceptance command requires Docker and boots the bundled Paper and Velocity jars against MySQL, MongoDB, Redis, and Redis messaging. See [Testing and CI](docs/TESTING_AND_CI.md) for the full test matrix and diagnostics.

Build outputs:

- `dataprovider-platform-paper/target/dataprovider-platform-paper-*-bundled.jar`
- `dataprovider-platform-velocity/target/dataprovider-platform-velocity-*-bundled.jar`

## Repository Layout

- `dataprovider-api`: stable, platform-neutral contracts, data-access types, and ORM integration surface.
- `dataprovider-core`: registry, configuration, storage drivers, and ORM implementation.
- `dataprovider-platform-common`: shared lifecycle, command, and logging adapters.
- `dataprovider-platform-paper` / `dataprovider-platform-velocity`: thin platform bootstraps and distributable bundles.

## Documentation

- [Documentation index](docs/README.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Usage guide](docs/USAGE_GUIDE.md)
- [Distributed coordination](docs/COORDINATION.md)
- [Configuration](docs/CONFIGURATION.md)
- [Development](docs/DEVELOPMENT.md)
- [Testing and CI](docs/TESTING_AND_CI.md)
- [Release process](docs/RELEASE.md)
- [Examples](docs/examples/README.md)

## Community and Governance

- [Contributing](CONTRIBUTING.md)
- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Security Policy](SECURITY.md)
- [Support](SUPPORT.md)

## License

This project is licensed under the [GNU Affero General Public License v3.0](LICENSE).
