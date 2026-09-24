# Release process

DataProvider publishes its own `dataprovider-bom`, which manages its public modules. Theme palette is its internal dependency.

Prepare a reviewed PR from a clean worktree with `./update_version.sh patch` (or `minor`/`major` for an intentional API change). The helper updates version metadata and leaves the changes for review. Merge only after the repository's CI passes. Do not create or push a release tag manually.

A version change on `main` starts `.github/workflows/release-package.yml`. The workflow runs the `integration-tests,platform-acceptance` release profiles, deploys the verified Maven reactor with `deployAtEnd`, resolves the published coordinates from an empty Maven repository, and only then creates tag `vX.Y.Z` and a GitHub Release with the bundled Paper and Velocity jars. The release dispatches HauntedPlatform's dependency reconciler, which proposes reviewed downstream PRs only after the package is available.

If publication fails before the tag, inspect whether any immutable coordinates were uploaded, fix the problem, then retry with `workflow_dispatch`. Do not overwrite a published version or move a tag. If dispatch fails after the tag, manually run HauntedPlatform's **Reconcile internal dependency PRs** workflow with this repository name and the published version. The [organization release guide](https://github.com/HauntedMC/HauntedPlatform/blob/main/docs/releasing.md) describes the graph and GitHub App setup.
