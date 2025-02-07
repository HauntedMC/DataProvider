buildtools_dir=~/buildtools
buildtools=$buildtools_dir/BuildTools.jar

get_buildtools () {
  if [[ -d $buildtools_dir && -f $buildtools ]]; then
    return
  fi

  mkdir $buildtools_dir
  wget https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar -O $buildtools
}

versions=$(. ./scripts/get_spigot_versions.sh)

if [ -n "$versions" ]; then
  echo Found Spigot dependencies: "$versions"

  for version in "${versions[@]}"; do
    set -e
    exit_code=0
    mvn dependency:get -Dartifact=org.spigotmc:spigot:"$version" -q -o || exit_code=$?
    if [ $exit_code -ne 0 ]; then
      echo Installing missing Spigot version "$version"
      revision=${version%%-R*}
      get_buildtools
      java -jar $buildtools -rev "$revision" --remapped
    else
      echo Spigot "$version" is already installed
    fi
  done
fi
