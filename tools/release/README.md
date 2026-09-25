# Version updates

Install the pinned shared CLI once with `gh extension install HauntedMC/gh-haunted-release --pin v1.0.1`. This folder keeps the project-specific version adapter and its configuration.

From clean, current `main`, run `./tools/release/update-version patch --pr` to prepare, commit, push, and open a reviewed PR. Omit `--pr` to prepare only a local diff; add `--dry-run` to inspect the next version without edits. The tool never merges, publishes, or tags.

Enabled repositories use their pull-request CI as the merge gate.
