if [[ ! $1 ]]; then
  echo "Please provide a version string."
  return
fi

mvn versions:set -DnewVersion="$1"

git add .
git commit -m "Bump version to $1 for release"
git tag -a -m "Release $1" "$1"
git push
git push --tags

mvn clean package -am -P all