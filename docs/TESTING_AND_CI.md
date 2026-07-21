# Testing and CI

This project uses Maven for unit tests, linting, coverage reporting, and real backend integration tests.

## Local Commands

Compile only:

```bash
mvn -B -ntp -DskipTests compile
```

Run fast unit tests only:

```bash
mvn -B -ntp test
```

Run full unit verification + coverage report without Docker-backed integration tests:

```bash
mvn -B -ntp verify
```

Run the complete verification suite, including Testcontainers integration tests for MySQL, MongoDB, and Redis:

```bash
mvn -B -ntp -Pintegration-tests verify
```

The integration suite requires a working Docker daemon. Integration tests use the `*IT` naming convention and are executed by Maven Failsafe only when the `integration-tests` profile is enabled. Normal unit-test runs therefore remain fast and do not start containers.

Run linting:

```bash
mvn -B -ntp -DskipTests checkstyle:check
```

JaCoCo HTML report:

- `<module>/target/site/jacoco/index.html`

Failsafe reports:

- `<module>/target/failsafe-reports/`

## Backend Integration Coverage

The Testcontainers suite verifies:

- successful MySQL, MongoDB, and Redis connections and health probes;
- rejected invalid credentials for all three backends;
- basic create, read, update, and delete behavior;
- committed and rolled-back MySQL transactions;
- MongoDB documents containing explicit `null` values;
- Redis key expiry;
- provider shutdown, closed resources, and cleared data-access handles.

## GitHub Actions Workflows

- `ci-lint.yml`
  - Trigger: pull requests + pushes to `main`
  - Job: Checkstyle lint
- `ci-tests-and-coverage.yml`
  - Trigger: pull requests + pushes to `main`
  - Job: `mvn -Pintegration-tests verify`
  - Runs unit tests and real MySQL, MongoDB, and Redis Testcontainers tests
  - Artifacts: JaCoCo reports (`**/target/site/jacoco`) and Failsafe reports (`**/target/failsafe-reports`)
- `release-package.yml`
  - Trigger: tag push `v*`
  - Job: package build, GitHub Packages deploy, GitHub Release creation
