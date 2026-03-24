# Testing and CI

This project uses Maven for unit tests, linting, and coverage reporting.

## Local Commands

Compile only:

```bash
mvn -B -ntp -DskipTests compile
```

Run unit tests:

```bash
mvn -B -ntp test
```

Run full verification + coverage report:

```bash
mvn -B -ntp verify
```

Run linting:

```bash
mvn -B -ntp -DskipTests checkstyle:check
```

JaCoCo HTML report:

- `target/site/jacoco/index.html`

## GitHub Actions Workflows

- `ci-lint.yml`
  - Trigger: pull requests + pushes to `main`
  - Job: Checkstyle lint
- `ci-tests-and-coverage.yml`
  - Trigger: pull requests + pushes to `main`
  - Job: `mvn verify`
  - Artifact: JaCoCo report (`target/site/jacoco`)
- `release-package.yml`
  - Trigger: tag push `v*`
  - Job: package build, GitHub Packages deploy, GitHub Release creation
