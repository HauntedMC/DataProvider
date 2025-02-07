# Get a pretty list of supported Minecraft versions
function get_minecraft_versions() {
  readarray -t versions <<< "$(. ./scripts/get_supported_versions.sh)"

  for version in "${versions[@]}"; do
    # Append comma if variable is set, then append version
    minecraft_versions="${minecraft_versions:+${minecraft_versions},}${version%%-R*}"
  done

  echo "${minecraft_versions}"
}

previous_tag=$(git describe --tags --abbrev=0 @^)

changelog=$(git log --pretty=format:"* %s (%h)" "$previous_tag"..@)

minecraft_versions=$(get_minecraft_versions)

printf "## Supported Minecraft versions\n%s\n\n## Changelog\n%s" "${minecraft_versions}" "${changelog}"