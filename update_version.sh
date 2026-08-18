#!/usr/bin/env bash
set -euo pipefail

readonly POM_FILE="pom.xml"
readonly README_FILE="README.md"
readonly VELOCITY_FILE="dataprovider-platform-velocity/src/main/java/nl/hauntedmc/dataprovider/platform/velocity/VelocityDataProvider.java"
readonly MAVEN_WRAPPER="./mvnw"
readonly VERSION_PROPERTY="revision"

die() {
  echo "Error: $*" >&2
  exit 1
}

usage() {
  cat >&2 <<'USAGE'
Usage: ./update_version.sh <major|minor|patch>

Bumps the Maven project version and keeps release metadata and dependency examples in sync.
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
    "$MAVEN_WRAPPER" -q -ntp -DforceStdout help:evaluate -Dexpression=project.version \
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

update_readme_dependency_versions() {
  local new_version="$1"
  local tmp_file
  tmp_file="$(mktemp)"

  awk -v v="$new_version" '
    BEGIN {
      in_api_dependency = 0
      maven_replaced = 0
      gradle_replaced = 0
    }
    {
      if ($0 ~ /<artifactId>dataprovider-api<\/artifactId>/) {
        in_api_dependency = 1
      } else if (in_api_dependency && !maven_replaced && $0 ~ /<version>[^<]+<\/version>/) {
        sub(/<version>[^<]+<\/version>/, "<version>" v "</version>")
        maven_replaced = 1
        in_api_dependency = 0
      }

      if (!gradle_replaced && $0 ~ /compileOnly "nl\.hauntedmc\.dataprovider:dataprovider-api:[^"]+"/) {
        sub(/dataprovider-api:[^"]+/, "dataprovider-api:" v)
        gradle_replaced = 1
      }

      print
    }
    END {
      if (!maven_replaced || !gradle_replaced) {
        exit 2
      }
    }
  ' "$README_FILE" > "$tmp_file" || {
    rm -f "$tmp_file"
    die "Could not update DataProvider API dependency versions in ${README_FILE}."
  }

  mv "$tmp_file" "$README_FILE"
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

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

require_file "$POM_FILE"
require_file "$README_FILE"
require_file "$VELOCITY_FILE"
[[ -x "$MAVEN_WRAPPER" ]] || die "Maven Wrapper (${MAVEN_WRAPPER}) is required and must be executable."
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

# The root POM's revision property is the single source of truth for every module.
"$MAVEN_WRAPPER" -B -ntp versions:set-property \
  -Dproperty="${VERSION_PROPERTY}" \
  -DnewVersion="${new_version}" \
  -DgenerateBackupPoms=false

resolved_after_bump="$(resolve_maven_version)"
[[ "$resolved_after_bump" == "$new_version" ]] || {
  die "Maven version after bump is '${resolved_after_bump}', expected '${new_version}'."
}

update_velocity_plugin_annotation "$new_version"
update_readme_dependency_versions "$new_version"

git add "$POM_FILE" "$README_FILE" "$VELOCITY_FILE"
git commit -m "Bump version to ${new_tag} for release"
git tag "$new_tag"

echo "Version updated locally."
echo "Next step: git push && git push origin ${new_tag}"
