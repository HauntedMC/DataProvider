#!/usr/bin/env bash
set -euo pipefail

readonly POM_FILE="pom.xml"
readonly VELOCITY_FILE="src/main/java/nl/hauntedmc/dataprovider/platform/velocity/VelocityDataProvider.java"

die() {
  echo "Error: $*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: ./update_version.sh <major|minor|patch>

Bumps the Maven project version in pom.xml and keeps release metadata in sync.
Then creates a local commit and a local git tag vX.Y.Z.
USAGE
}

require_file() {
  local path="$1"
  [[ -f "$path" ]] || die "${path} not found."
}

require_clean_worktree() {
  [[ -z "$(git status --porcelain)" ]] || die "Working tree is not clean. Commit or stash changes first."
}

resolve_maven_version() {
  local version
  version="$(
    mvn -q -ntp -DforceStdout help:evaluate -Dexpression=project.version \
      | awk '/^[0-9]+\.[0-9]+\.[0-9]+$/ { print; exit }'
  )"
  [[ -n "$version" ]] || die "Unable to resolve a release semantic version from Maven."
  echo "$version"
}

bump_semver() {
  local semver="$1"
  local bump_type="$2"
  local major minor patch

  [[ "$semver" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || die "Current version must be semantic (X.Y.Z), got '${semver}'."

  major="${BASH_REMATCH[1]}"
  minor="${BASH_REMATCH[2]}"
  patch="${BASH_REMATCH[3]}"

  case "$bump_type" in
    major)
      major=$((major + 1))
      minor=0
      patch=0
      ;;
    minor)
      minor=$((minor + 1))
      patch=0
      ;;
    patch)
      patch=$((patch + 1))
      ;;
    *)
      usage
      exit 1
      ;;
  esac

  echo "${major}.${minor}.${patch}"
}

update_velocity_plugin_annotation() {
  local new_version="$1"
  local tmp_file
  tmp_file="$(mktemp)"

  awk -v v="$new_version" '
    BEGIN { replaced = 0 }
    {
      if (!replaced && $0 ~ /version = "[^"]+"/) {
        sub(/version = "[^"]+"/, "version = \"" v "\"")
        replaced = 1
      }
      print
    }
    END {
      if (!replaced) {
        exit 2
      }
    }
  ' "$VELOCITY_FILE" > "$tmp_file" || {
    rm -f "$tmp_file"
    die "Could not update Velocity @Plugin version in ${VELOCITY_FILE}."
  }

  mv "$tmp_file" "$VELOCITY_FILE"
}

if [[ $# -eq 1 && ( "$1" == "--help" || "$1" == "-h" ) ]]; then
  usage
  exit 0
fi

if [[ $# -ne 1 ]]; then
  usage
  exit 1
fi

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  die "This script must be run inside a git repository."
fi

command -v mvn >/dev/null 2>&1 || die "Maven (mvn) is required."

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

require_file "$POM_FILE"
require_file "$VELOCITY_FILE"
require_clean_worktree

bump_type="$1"
[[ "$bump_type" == "major" || "$bump_type" == "minor" || "$bump_type" == "patch" ]] || {
  usage
  exit 1
}

current_version="$(resolve_maven_version)"
new_version="$(bump_semver "$current_version" "$bump_type")"
new_tag="v${new_version}"

if git rev-parse -q --verify "refs/tags/${new_tag}" >/dev/null 2>&1; then
  die "Tag ${new_tag} already exists."
fi

echo "Current version: ${current_version}"
echo "Bumping to: ${new_version}"

# Use Maven's versions plugin so pom.xml remains the single source of truth.
mvn -B -ntp versions:set -DnewVersion="${new_version}" -DgenerateBackupPoms=false -DprocessAllModules=true

resolved_after_bump="$(resolve_maven_version)"
[[ "$resolved_after_bump" == "$new_version" ]] || {
  die "Maven version after bump is '${resolved_after_bump}', expected '${new_version}'."
}

update_velocity_plugin_annotation "$new_version"

git add "$POM_FILE" "$VELOCITY_FILE"
git commit -m "Bump version to ${new_tag} for release"
git tag "$new_tag"

echo "Version updated locally."
echo "Next step: git push && git push origin ${new_tag}"
