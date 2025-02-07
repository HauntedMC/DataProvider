function get_name() {
  mvn -q -Dexec.executable=echo -Dexec.args='${project.name}' --non-recursive exec:exec
}

echo "TARGET_NAME=$(get_name)" >> "$GITHUB_ENV"
