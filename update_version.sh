#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <major|minor|patch>" >&2
}

if [[ $# -ne 1 ]]; then
  usage
  exit 1
fi

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "This script must be run inside a git repository." >&2
  exit 1
fi

bump_type="$1"
if [[ "$bump_type" != "major" && "$bump_type" != "minor" && "$bump_type" != "patch" ]]; then
  usage
  exit 1
fi

if [[ ! -f version.txt ]]; then
  echo "version.txt not found." >&2
  exit 1
fi

if [[ ! -f pom.xml ]]; then
  echo "pom.xml not found." >&2
  exit 1
fi

if [[ ! -f README.md ]]; then
  echo "README.md not found." >&2
  exit 1
fi

velocity_file="src/main/java/nl/hauntedmc/dataprovider/platform/velocity/VelocityDataProvider.java"
if [[ ! -f "$velocity_file" ]]; then
  echo "${velocity_file} not found." >&2
  exit 1
fi

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Working tree is not clean. Commit or stash changes before bumping version." >&2
  exit 1
fi

current_version="$(<version.txt)"
if [[ ! "$current_version" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
  echo "version.txt must contain a semantic version like v1.2.3." >&2
  exit 1
fi

major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"

current_raw_version="${major}.${minor}.${patch}"
pom_raw_version="$(grep -m1 -oE '<version>[0-9]+\.[0-9]+\.[0-9]+</version>' pom.xml | sed -E 's#</?version>##g')"
if [[ "$pom_raw_version" != "$current_raw_version" ]]; then
  echo "version.txt (${current_version}) does not match pom.xml (${pom_raw_version})." >&2
  exit 1
fi

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
esac

new_version="v${major}.${minor}.${patch}"
new_raw_version="${major}.${minor}.${patch}"

if git rev-parse -q --verify "refs/tags/${new_version}" >/dev/null 2>&1; then
  echo "Tag ${new_version} already exists." >&2
  exit 1
fi

echo "New version: $new_version"
printf '%s\n' "$new_version" > version.txt

# Update project version (first <version> in pom.xml = project version).
awk -v v="$new_raw_version" '
  BEGIN { replaced = 0 }
  {
    if (!replaced && $0 ~ /<version>[0-9]+\.[0-9]+\.[0-9]+<\/version>/) {
      sub(/<version>[0-9]+\.[0-9]+\.[0-9]+<\/version>/, "<version>" v "</version>")
      replaced = 1
    }
    print
  }
' pom.xml > pom.xml.tmp
mv pom.xml.tmp pom.xml

# Keep runtime metadata in sync for Velocity.
sed -E -i "s/(version = \")[0-9]+\.[0-9]+\.[0-9]+(\")/\1${new_raw_version}\2/" "$velocity_file"

# Keep dependency examples in README in sync.
sed -i "s/${current_raw_version}/${new_raw_version}/g" README.md

git add version.txt pom.xml README.md "$velocity_file"
git commit -m "Bump version to $new_version for release"
git tag "$new_version"

echo "Version updated locally. Push the branch and tag when ready:"
echo "  git push && git push origin $new_version"
