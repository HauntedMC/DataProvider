# Version updates

Install the pinned shared CLI once with `gh extension install HauntedMC/gh-haunted-release --pin v1.0.2`. This folder keeps the project-specific version adapter and its configuration.

If an older pinned extension is installed, run `gh extension remove haunted-release` and then the install command above. Check `gh haunted-release --version` before preparing a release.

From clean, current `main`, run `./tools/release/update-version patch --pr` to prepare, commit, push, and open a PR from an isolated worktree. Retry the same command if PR creation fails; it checks the pushed branch again. Omit `--pr` to prepare only a local diff; add `--dry-run` to inspect the next version without edits. The tool never merges, publishes, or tags.

Enabled repositories use their pull-request CI as the merge gate.
