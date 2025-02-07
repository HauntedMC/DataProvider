declare -n versions="supported_versions"

max_index=$(mvn help:evaluate -Dexpression=project.dependencies -q -DforceStdout | grep -c "<org.apache.maven.model.Dependency>")

for ((i=0; i < max_index; i++)); do
  artifact_id=$(mvn help:evaluate -Dexpression=project.dependencies["$i"].artifactId -q -DforceStdout)

  if [[ "$artifact_id" == spigot-api ]] || [[ "$artifact_id" == paper-api ]]; then
    supported_version=$(mvn help:evaluate -Dexpression=project.dependencies["$i"].version -q -DforceStdout)
    versions+=("$supported_version")
    echo "$supported_version"
    break
  fi
done
