# Release Process

## 1. Prepare

- Work from a clean `main` branch.
- Ensure CI is green.
- Verify local checks:

```bash
./mvnw -B -ntp -Pintegration-tests,platform-acceptance verify
```

## 2. Bump and Tag

Use `update_version.sh` to bump `major`, `minor`, or `patch`:

```bash
./update_version.sh patch
```

The script uses the repository Maven Wrapper and updates:

- `pom.xml` (`revision` via Maven `versions:set-property`; source of truth for every module)
- `dataprovider-platform-velocity/src/main/java/nl/hauntedmc/dataprovider/platform/velocity/VelocityDataProvider.java`
- the Maven and Gradle `dataprovider-api` dependency examples in `README.md`

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

1. Checks that the tag matches `revision`.
2. Runs the full reactor release gate on the tag checkout: unit tests, container-backed
   backend integration tests, Checkstyle, JaCoCo thresholds, dependency convergence,
   upper-bound dependency checks, duplicate-class checks, shaded platform packaging, and
   the API-only Paper and Velocity consumer fixtures.
3. As the final reactor module, downloads the pinned Paper and Velocity runtime builds with SHA-256 verification,
   starts each real platform with its bundled DataProvider JAR, and validates MySQL,
   MongoDB, Redis, Redis messaging, configuration reload, per-provider thread cleanup,
   and clean shutdown.
4. Uses Maven `deployAtEnd`, so it deploys the already-verified Maven artifacts only after every gate succeeds; the acceptance fixtures themselves are not published.
5. Uploads the Paper and Velocity bundled jars and creates a GitHub Release with them.

The platform acceptance runner is at
`dataprovider-platform-acceptance/run-platform-acceptance.sh`. It uses the pinned runtime
build properties and SHA-256 checksums in the root POM; update the corresponding version,
build, and checksum together whenever the Paper or Velocity API dependency is advanced.

## 4. Artifacts

- Repository: `https://maven.pkg.github.com/HauntedMC/DataProvider`
- GroupId: `nl.hauntedmc.dataprovider`
- ArtifactIds: `dataprovider-api`, `dataprovider-core`, `dataprovider-platform-common`, `dataprovider-platform-paper`, and `dataprovider-platform-velocity`
- Version: release version (without leading `v`)
