# Testing and CI

This project uses Maven for unit tests, linting, coverage reporting, and real backend integration tests.

## Local Commands

Compile only:

```bash
./mvnw -B -ntp -DskipTests compile
```

Run fast unit tests only:

```bash
./mvnw -B -ntp test
```

Run full unit verification + coverage report without Docker-backed integration tests:

```bash
./mvnw -B -ntp verify
```

Run the complete verification suite, including Testcontainers integration tests for MySQL, MongoDB, and Redis:

```bash
./mvnw -B -ntp -Pintegration-tests verify
```

The integration suite requires a working Docker daemon. Integration tests use the `*IT` naming convention and are executed by Maven Failsafe only when the `integration-tests` profile is enabled. Normal unit-test runs therefore remain fast and do not start containers.

Run the bundled Paper and Velocity acceptance gate:

```bash
./mvnw -B -ntp -Pplatform-acceptance verify
```

This starts pinned MySQL, MongoDB, and Redis containers on dynamically allocated loopback ports, downloads the pinned Paper and Velocity runtime binaries, and retains no local state after a successful run. Set `PLATFORM_ACCEPTANCE_WORK_DIRECTORY` and `PLATFORM_ACCEPTANCE_KEEP_WORK_DIRECTORY=true` to retain the complete platform and backend logs for diagnosis.

Run linting:

```bash
./mvnw -B -ntp -DskipTests checkstyle:check
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
  - Jobs: Checkstyle and ShellCheck for the platform runner
- `ci-tests-and-coverage.yml`
  - Trigger: pull requests + pushes to `main`
  - `Tests and Coverage`: `./mvnw -Pintegration-tests verify`
  - `Bundled Platform Acceptance`: packages the real platform jars and boots them in Paper and Velocity
  - Runs unit tests, real MySQL/MongoDB/Redis Testcontainers tests, and the bundled-artifact consumer tests before merge
  - Artifacts: JaCoCo reports (`**/target/site/jacoco`) and Failsafe reports (`**/target/failsafe-reports`)
  - Platform logs are uploaded for every acceptance run
- `release-package.yml`
  - Trigger: tag push `v*`
  - Job: package build, GitHub Packages deploy, GitHub Release creation
