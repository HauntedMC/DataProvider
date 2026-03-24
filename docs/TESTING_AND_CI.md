# Testing and CI

This project uses Maven for unit tests, linting, and coverage reporting.

## Local Commands

- Run unit tests:
  - `mvn -B -ntp test`
- Run full verification including JaCoCo report:
  - `mvn -B -ntp verify`
- Run Checkstyle:
  - `mvn -B -ntp -DskipTests checkstyle:check`

JaCoCo HTML output is generated at:
- `target/site/jacoco/index.html`

## GitHub Actions Pipeline

Two workflows are configured under `.github/workflows`:

- `ci-lint.yml`
  - Triggers on pushes to `main` and pull requests.
  - Runs Checkstyle (`mvn -B -ntp -DskipTests checkstyle:check`).

- `ci-tests-and-coverage.yml`
  - Triggers on pushes to `main` and pull requests.
  - Runs `mvn -B -ntp verify`.
  - Uploads the JaCoCo report artifact from `target/site/jacoco`.

## Test Scope

The unit suite focuses on:
- API contracts and default interface behavior.
- Config parsing and defaults injection.
- Registry lifecycle/reference-counting behavior.
- SQL data access and schema builder behavior through mocked JDBC interfaces.
- Command logic and logger adapters.

Some classes are integration-heavy (platform bootstrap and concrete Redis/Mongo runtime clients) and are harder to unit test without runtime dependencies. The suite still validates their guard/validation branches where possible, and all classes remain included in JaCoCo reporting.
