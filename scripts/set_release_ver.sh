function get_versioned_name() {
  mvn -q -Dexec.executable=echo -Dexec.args='${project.name} ${project.version}' --non-recursive exec:exec
}

echo "VERSIONED_NAME=$(get_versioned_name)" >> "$GITHUB_ENV"

changelog="$(. ./scripts/generate_changelog.sh)"
printf "GENERATED_CHANGELOG<<EOF\n%s\nEOF\n" "$changelog" >> "$GITHUB_ENV"