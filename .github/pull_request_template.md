## What Changed

Describe the change in a few sentences. Keep the scope focused and call out public API, configuration, runtime, or packaging changes explicitly.

## Why

Why is this needed, and what developer/operator problem does it solve?

## Validation

- [ ] `./mvnw -q -DskipTests compile`
- [ ] `./mvnw -q test`
- [ ] `./mvnw -B -ntp -Pintegration-tests verify` (backend/runtime changes, or explain why not applicable)
- [ ] `./mvnw -B -ntp -Pplatform-acceptance verify` (platform/bundle changes, or explain why not applicable)
- [ ] Manual testing done (if applicable)

## Compatibility

- [ ] Existing public API signatures remain compatible, or migration notes are included.
- [ ] Existing configuration remains valid, or migration/default reconciliation is documented.
- [ ] Storage/wire formats remain compatible, or the compatibility impact is documented.

## Related Issues

List related issues (example: `Closes #123`), or write `None`.

## Notes

- Breaking changes:
- Config/API changes:
- Anything reviewers should pay extra attention to:
