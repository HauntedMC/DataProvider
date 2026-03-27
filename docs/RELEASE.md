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

- `pom.xml` (via Maven `versions:set`; source of truth)
- `src/main/java/nl/hauntedmc/dataprovider/platform/velocity/VelocityDataProvider.java`

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

1. Builds the jar.
2. Uploads the distributable artifact.
3. Deploys Maven package to GitHub Packages.
4. Creates a GitHub Release with generated notes and attached jar.

## 4.  Artifacts

- Repository: `https://maven.pkg.github.com/HauntedMC/DataProvider`
- GroupId: `nl.hauntedmc.dataprovider`
- ArtifactId: `dataprovider`
- Version: release version (without leading `v`)
