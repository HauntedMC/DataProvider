# Architecture Review

Reviewed on: 2026-03-24

## Scope

- Public API ergonomics (`DataProviderAPI`, `DatabaseProvider` helpers)
- Runtime safety in connection/config resolution internals
- Documentation and test coverage quality

## Strengths

- Clear platform boundary (`BukkitDataProvider`, `VelocityDataProvider`) with a shared internal core.
- Plugin-scoped identity model protects privileged operations from external callers.
- Unified abstraction over relational, document, key-value, and messaging backends.
- Reference-counted connection registry prevents duplicate connection churn.

## Key Findings (Before Improvements)

- Test coverage was effectively absent (`src/test/java/Test.java` placeholder only).
- Bundled database templates used `default_credentials`, while docs/examples/integration commonly used `default`.
- API default helper methods had null/readiness edge-cases that could produce unclear failures.
- Missing docs files referenced by README reduced maintainability and onboarding quality.

## Improvements Applied

- Added real JUnit 5 unit tests for:
  - `DatabaseProvider` helper semantics (`Optional` + `require*` behaviors)
  - `DatabaseConfigMap` identifier fallback and diagnostics
  - Document query/update builder input validation
- Improved API helper behavior:
  - `getDataAccessOptional(...)` now safely handles not-ready providers.
  - `requireDataAccess(...)` now reports null provider data access explicitly.
  - `getDataSourceOptional()` now tolerates unsupported and not-ready provider states.
- Improved config usability:
  - Added compatibility alias resolution between `default` and `default_credentials`.
  - Added clearer missing-section warnings with available section names.
  - Updated bundled config templates to include `default` as the primary identifier.
  - Added `player_data_rw` MySQL template for common ORM write usage.
- Hardened registry lookups:
  - Stale/disconnected providers are now evicted during lookup, not just during registration.

## Residual Risks / Next Steps

- Most tests are unit-level; no automated integration tests currently validate real MySQL/Mongo/Redis instances.
- Memcached implementations exist but are not reachable through `DatabaseType`; either expose or remove to reduce dead surface area.
- Consider adding CI quality gates (`checkstyle`, test coverage thresholds, and smoke integration matrix).
