declare -a spigot_versions

# shellcheck disable=SC2199
if [[ ${spigot_versions[@]} ]]; then
  for spigot_version in "${spigot_versions[@]}"; do
    echo "$spigot_version"
  done
  return
fi

declare -n versions="spigot_versions"

max_index=$(mvn help:evaluate -Dexpression=project.dependencies -q -DforceStdout | grep -c "<org.apache.maven.model.Dependency>")

for ((i=0; i < max_index; i++)); do
  artifact_id=$(mvn help:evaluate -Dexpression=project.dependencies["$i"].artifactId -q -DforceStdout)

  if [[ "$artifact_id" == spigot ]]; then
    spigot_version=$(mvn help:evaluate -Dexpression=project.dependencies["$i"].version -q -DforceStdout)
    versions+=("$spigot_version")
    echo "$spigot_version"
    break
  fi
done
