#!/bin/bash

# Read the current version from pom.xml
current_version=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)

# Parse the major, minor, and patch numbers
IFS='.' read -ra version_parts <<< "$current_version"
major=${version_parts[0]}
minor=${version_parts[1]}
patch=${version_parts[2]}

# Define the increment function
increment_version() {
    if [[ $1 == "major" ]]; then
        major=$((major + 1))
        minor=0
        patch=0
    elif [[ $1 == "minor" ]]; then
        minor=$((minor + 1))
        patch=0
    elif [[ $1 == "patch" ]]; then
        patch=$((patch + 1))
    else
        echo "Invalid input. Please specify 'major', 'minor', or 'patch'."
        exit 1
    fi
}

# Increment the version based on the input
increment_version "$1"

# Create the new version string
new_version="${major}.${minor}.${patch}"

# Print the new version
echo "New version: $new_version"

mvn versions:set -DnewVersion=$new_version

formatted_new_version="v${new_version}"

git add pom.xml

git commit -m "Bump version to $formatted_new_version for release"
git tag -m "Release $formatted_new_version" $formatted_new_version
git push
git push origin $formatted_new_version