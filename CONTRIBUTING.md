# Contributing to DataProvider

Thanks for contributing.

## Prerequisites

- Java 25
- Docker for integration and bundled-platform acceptance checks

## Development Setup

```bash
git clone git@github.com:HauntedMC/DataProvider.git
cd DataProvider
./mvnw -q -DskipTests compile
```

## Project Layout

- `dataprovider-api`: public contracts for consuming plugins
- `dataprovider-core`: configuration, lifecycle, drivers, and ORM implementation
- `dataprovider-platform-*`: shared and host-specific runtime adapters
- Each module owns its own `src/main`, `src/test`, and resources
- `docs/`: developer and operational documentation
- `.github/`: CI workflows, issue templates, PR template

## Branching and Commits

- Branch from `main`.
- Keep commits focused and easy to review.
- Use clear commit messages: `type: summary`.

Examples:

- `fix: prevent stale provider reuse after disconnect`
- `docs: add release and configuration guides`

## Development Expectations

- Prefer Optional-first helper APIs over nullable/cast-heavy call sites.
- Keep platform integration thin and reuse shared internal components.
- Ensure registration and cleanup paths remain lifecycle-safe.
- Treat external IO and payload parsing as untrusted; fail safely.
- Keep logs actionable and avoid leaking secrets/credentials.

## Validation Before PR

Minimum:

```bash
./mvnw -q -DskipTests compile
./mvnw -q test
```

Recommended:

```bash
./mvnw -B -ntp -Pintegration-tests verify
./mvnw -B -ntp -Pplatform-acceptance verify
./mvnw -B -ntp -DskipTests checkstyle:check
```

The Maven Wrapper downloads the repository-pinned Maven version. The platform-acceptance gate requires Docker and starts real Paper and Velocity runtimes with the bundled artifacts; use it for platform, backend, configuration reload, or shading changes.

## Pull Requests

- Fill out the PR template.
- Link related issues.
- Document API/config changes and migration notes when relevant.
- Add or update tests for behavior changes.
- Update docs when behavior or setup changes.

## Security

Do not open public issues for vulnerabilities.
Use the process in [SECURITY.md](SECURITY.md).
