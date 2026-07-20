# Release Process

## 1. Prepare

- Work from a clean `main` branch.
- Ensure CI is green.
- Verify local checks:

```bash
mvn -B -ntp verify
mvn -B -ntp -DskipTests checkstyle:check
```

## 2. Bump and Tag

Use `update_version.sh` to bump `major`, `minor`, or `patch`:

```bash
./update_version.sh patch
```

The script updates:

- `pom.xml` (`revision` via Maven `versions:set-property`; source of truth for every module)
- `dataprovider-platform-velocity/src/main/java/nl/hauntedmc/dataprovider/platform/velocity/VelocityDataProvider.java`

Manual step:

- Update README dependency version examples if needed.

Then it commits and tags (`vX.Y.Z`) locally.
Push when ready:

```bash
git push && git push origin vX.Y.Z
```

## 3. GitHub Actions Release

Workflow: `.github/workflows/release-package.yml`

Trigger:

- push tag matching `v*` (for example `v1.20.5`)

What it does:

1. Verifies the full reactor and checks that the tag matches `revision`.
2. Uploads the Paper and Velocity bundled jars.
3. Deploys every Maven module to GitHub Packages.
4. Creates a GitHub Release with generated notes and both platform jars.

## 4.  Artifacts

- Repository: `https://maven.pkg.github.com/HauntedMC/DataProvider`
- GroupId: `nl.hauntedmc.dataprovider`
- ArtifactIds: `dataprovider-api`, `dataprovider-core`, `dataprovider-platform-common`, `dataprovider-platform-paper`, and `dataprovider-platform-velocity`
- Version: release version (without leading `v`)
