# DataProvider

[![CI Tests and Coverage](https://github.com/HauntedMC/DataProvider/actions/workflows/ci-tests-and-coverage.yml/badge.svg?branch=main)](https://github.com/HauntedMC/DataProvider/actions/workflows/ci-tests-and-coverage.yml)
[![CI Lint](https://github.com/HauntedMC/DataProvider/actions/workflows/ci-lint.yml/badge.svg?branch=main)](https://github.com/HauntedMC/DataProvider/actions/workflows/ci-lint.yml)
[![Release](https://img.shields.io/github/v/release/HauntedMC/DataProvider)](https://github.com/HauntedMC/DataProvider/releases)
[![License](https://img.shields.io/github/license/HauntedMC/DataProvider)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-007396)](https://adoptium.net/)

Build plugin features, not database plumbing.

`DataProvider` is shared infrastructure for plugin developers on Velocity and Bukkit/Paper.  
It gives you one clean API for MySQL, MongoDB, Redis, and Redis messaging so your plugin code can stay focused on gameplay and business logic.

## Why Use DataProvider?

- Faster development: stop rewriting connection, pooling, and lifecycle code in every plugin.
- Consistent developer experience: same registration and access flow across multiple backends.
- Safer multi-plugin setup: caller-scoped access rules prevent cross-plugin misuse.
- Cleaner codebase: typed APIs reduce casting and repetitive boilerplate.
- Better runtime behavior: connection reuse and lifecycle cleanup are handled centrally.

## What You Get

- Unified backend support: `MYSQL`, `MONGODB`, `REDIS`, `REDIS_MESSAGING`
- Platform support: Velocity + Bukkit/Paper
- Reference-counted connection lifecycle management
- Optional ORM support for relational workflows (`ORMContext`)

## Requirements

- Java 21
- Velocity `3.5.0-SNAPSHOT` and/or Paper API `1.21.11-R0.1-SNAPSHOT` (compile targets)
- MySQL, MongoDB, and/or Redis for the backends you enable

## Quick Start

```java
DataProviderAPI api = VelocityDataProvider.getDataProviderAPI();

Optional<RelationalDatabaseProvider> mysql = api.registerDatabaseAs(
        DatabaseType.MYSQL,
        "default",
        RelationalDatabaseProvider.class
);

if (mysql.isEmpty() || !mysql.get().isConnected()) {
    // Handle unavailable connection
    return;
}

api.unregisterDatabase(DatabaseType.MYSQL, "default");
```

If you maintain multiple plugins, this gives your team one standard integration model instead of backend-specific code per project.

## Install DataProvider (Server)

1. Build or download `DataProvider.jar`.
2. Put it in your server `plugins/` directory.
3. Start once to generate default configuration.
4. Configure `plugins/DataProvider/config.yml` and `plugins/DataProvider/databases/*.yml`.

## Add It to Your Plugin Project

Coordinates:

- `groupId`: `nl.hauntedmc.dataprovider`
- `artifactId`: `dataprovider`
- `version`: current release (example: `1.20.4`)

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
  <artifactId>dataprovider</artifactId>
  <version>1.20.4</version>
  <scope>provided</scope>
</dependency>
```

Gradle (Groovy):

```groovy
compileOnly "nl.hauntedmc.dataprovider:dataprovider:1.20.4"
```

GitHub Packages authentication details are in the docs.

## Build

```bash
mvn -q -DskipTests compile
mvn -q test
mvn -B verify
mvn -B -DskipTests checkstyle:check
mvn -B package
```

Build output:

- `target/DataProvider.jar`

## Documentation

- [Documentation index](docs/README.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Usage guide](docs/USAGE_GUIDE.md)
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
