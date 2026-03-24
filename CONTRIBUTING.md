# Contributing to DataProvider

Thanks for contributing.

## Prerequisites

- Java 21
- Maven 3.9+
- Local Velocity and/or Bukkit/Paper environment for manual validation
- Optional local MySQL, MongoDB, and Redis instances for integration checks

## Development Setup

```bash
git clone git@github.com:HauntedMC/DataProvider.git
cd DataProvider
mvn -q -DskipTests compile
```

## Project Layout

- `src/main/java`: implementation
- `src/main/resources`: default plugin/database configuration
- `src/test/java`: unit tests (mirrors runtime packages)
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
mvn -q -DskipTests compile
mvn -q test
```

Recommended:

```bash
mvn -B verify
mvn -B -DskipTests checkstyle:check
```

## Pull Requests

- Fill out the PR template.
- Link related issues.
- Document API/config changes and migration notes when relevant.
- Add or update tests for behavior changes.
- Update docs when behavior or setup changes.

## Security

Do not open public issues for vulnerabilities.
Use the process in [SECURITY.md](SECURITY.md).
