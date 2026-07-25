#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ACCEPTANCE_DIRECTORY="$ROOT_DIRECTORY/dataprovider-platform-acceptance"
readonly COMPOSE_FILE="$ACCEPTANCE_DIRECTORY/docker-compose.yml"
readonly USER_AGENT="DataProvider-release-validation/1.0 (https://github.com/HauntedMC/DataProvider)"
readonly WORK_DIRECTORY="$(mktemp -d)"

paper_process=""
paper_input_fd=""
velocity_process=""
velocity_input_fd=""

cleanup() {
    local exit_code=$?
    if [[ -n "$paper_process" ]] && kill -0 "$paper_process" 2>/dev/null; then
        kill "$paper_process" 2>/dev/null || true
    fi
    if [[ -n "$velocity_process" ]] && kill -0 "$velocity_process" 2>/dev/null; then
        kill "$velocity_process" 2>/dev/null || true
    fi
    if [[ -n "$paper_input_fd" ]]; then
        eval "exec ${paper_input_fd}>&-" || true
    fi
    if [[ -n "$velocity_input_fd" ]]; then
        eval "exec ${velocity_input_fd}>&-" || true
    fi
    docker compose --file "$COMPOSE_FILE" logs --no-color >"$WORK_DIRECTORY/backend.log" 2>&1 || true
    docker compose --file "$COMPOSE_FILE" down --volumes --timeout 10 >/dev/null 2>&1 || true
    if [[ $exit_code -ne 0 ]]; then
        find "$WORK_DIRECTORY" -maxdepth 2 -name '*.log' -type f -print -exec tail -n 250 {} \; >&2 || true
    fi
    rm -rf "$WORK_DIRECTORY"
    exit "$exit_code"
}
trap cleanup EXIT

fail() {
    echo "Platform acceptance failure: $*" >&2
    exit 1
}

pom_property() {
    local property_name=$1
    local value
    value="$(rg -o "<${property_name}>[^<]+" "$ROOT_DIRECTORY/pom.xml" | head -n 1 | sed "s#<${property_name}>##")"
    [[ -n "$value" ]] || fail "Missing Maven property ${property_name}."
    printf '%s' "$value"
}

download_runtime() {
    local project=$1
    local version=$2
    local build=$3
    local destination=$4
    local build_metadata url checksum actual_checksum
    build_metadata="$(curl --fail --silent --show-error --location --header "User-Agent: $USER_AGENT" \
        "https://fill.papermc.io/v3/projects/${project}/versions/${version}/builds")"
    url="$(jq --raw-output --argjson build "$build" \
        '.[] | select(.id == $build) | .downloads["server:default"].url' <<<"$build_metadata")"
    checksum="$(jq --raw-output --argjson build "$build" \
        '.[] | select(.id == $build) | .downloads["server:default"].checksums.sha256' <<<"$build_metadata")"
    [[ "$url" != "null" && "$checksum" != "null" ]] || fail "No ${project} ${version} build ${build} server download exists."
    curl --fail --silent --show-error --location --output "$destination" "$url"
    actual_checksum="$(sha256sum "$destination" | awk '{print $1}')"
    [[ "$actual_checksum" == "$checksum" ]] || fail "Checksum mismatch for ${project} ${version} build ${build}."
}

wait_for_healthy_backends() {
    local service status attempts=0
    while (( attempts++ < 90 )); do
        for service in mysql mongodb redis; do
            status="$(docker compose --file "$COMPOSE_FILE" ps --format json "$service" | jq -r '.Health // empty')"
            [[ "$status" == "healthy" ]] || break
        done
        [[ "$service" == "redis" && "$status" == "healthy" ]] && return
        sleep 1
    done
    fail "Timed out waiting for MySQL, MongoDB and Redis health checks."
}

wait_for_log() {
    local log_file=$1
    local expected=$2
    local timeout_seconds=$3
    local deadline=$((SECONDS + timeout_seconds))
    while (( SECONDS < deadline )); do
        if rg -q "$expected" "$log_file"; then
            return
        fi
        if rg -q 'DATAPROVIDER_ACCEPTANCE_FAIL|Exception in thread|Could not load' "$log_file"; then
            fail "Platform reported an acceptance or boot failure while waiting for ${expected}."
        fi
        sleep 1
    done
    fail "Timed out waiting for ${expected}."
}

stop_process() {
    local process_id=$1
    local input_fd=$2
    local stop_command=$3
    local log_file=$4
    local deadline
    eval "printf '%s\\n' '$stop_command' >&${input_fd}"
    deadline=$((SECONDS + 45))
    while kill -0 "$process_id" 2>/dev/null && (( SECONDS < deadline )); do
        sleep 1
    done
    if kill -0 "$process_id" 2>/dev/null; then
        fail "Platform did not terminate after ${stop_command}."
    fi
    wait "$process_id" || fail "Platform exited unsuccessfully after ${stop_command}."
    rg -q 'DataProvider disabled' "$log_file" || fail "DataProvider did not report clean platform shutdown."
}

write_dataprovider_configuration() {
    local data_directory=$1
    local owner_plugin=$2
    local connection_identifier=$3
    mkdir -p "$data_directory/databases"
    cp "$ROOT_DIRECTORY/dataprovider-core/src/main/resources/config.yml" "$data_directory/config.yml"
    cat >"$data_directory/databases/mysql.yml" <<EOF
${connection_identifier}:
  access:
    owner_plugin: "${owner_plugin}"
    shared_with: []
  host: 127.0.0.1
  port: 3307
  database: minecraft
  username: root
  password: acceptance-root
  ssl_mode: DISABLED
  pool_size: 2
  min_idle: 0
  connection_timeout_ms: 5000
  validation_timeout_ms: 2000
  connect_timeout_ms: 5000
  socket_timeout_ms: 5000
EOF
    cat >"$data_directory/databases/mongodb.yml" <<EOF
${connection_identifier}:
  access:
    owner_plugin: "${owner_plugin}"
    shared_with: []
  host: 127.0.0.1
  port: 27018
  database: minecraft
  authSource: minecraft
  max_connection_pool_size: 4
  connect_timeout_ms: 5000
  socket_timeout_ms: 5000
  server_selection_timeout_ms: 5000
EOF
    cat >"$data_directory/databases/redis.yml" <<EOF
${connection_identifier}:
  access:
    owner_plugin: "${owner_plugin}"
    shared_with: []
  host: 127.0.0.1
  port: 6380
  password: acceptance-redis
  pool:
    connections: 2
    min_idle: 0
    max_idle: 2
  connection_timeout_ms: 2000
  socket_timeout_ms: 2000
EOF
    cat >"$data_directory/databases/redis_messaging.yml" <<EOF
${connection_identifier}:
  access:
    owner_plugin: "${owner_plugin}"
    shared_with: []
  host: 127.0.0.1
  port: 6380
  password: acceptance-redis
  pool:
    connections: 2
    min_idle: 0
    max_idle: 2
    max_subscriptions: 4
    handler_batch_size: 8
  reconnect:
    initial_backoff_ms: 50
    max_backoff_ms: 500
    jitter: 0.10
    max_attempts: 0
  connection_timeout_ms: 2000
  socket_timeout_ms: 2000
EOF
}

start_paper() {
    local directory="$WORK_DIRECTORY/paper"
    mkdir -p "$directory/plugins/DataProvider"
    cp "$PAPER_BUNDLE" "$directory/plugins/DataProvider.jar"
    cp "$PAPER_CONSUMER" "$directory/plugins/DataProviderAcceptance.jar"
    write_dataprovider_configuration "$directory/plugins/DataProvider" "DataProviderAcceptance" paper
    printf 'eula=true\n' >"$directory/eula.txt"
    mkfifo "$directory/console.in"
    (
        cd "$directory"
        java -Xms512M -Xmx1G -jar "$WORK_DIRECTORY/paper.jar" --nogui <console.in >paper.log 2>&1
    ) &
    paper_process=$!
    exec {paper_input_fd}>"$directory/console.in"
    wait_for_log "$directory/paper.log" 'DATAPROVIDER_ACCEPTANCE_PASS platform=paper' 180
    stop_process "$paper_process" "$paper_input_fd" stop "$directory/paper.log"
    eval "exec ${paper_input_fd}>&-"
    paper_input_fd=""
    paper_process=""
}

start_velocity() {
    local directory="$WORK_DIRECTORY/velocity"
    mkdir -p "$directory/plugins/dataprovider"
    cp "$VELOCITY_BUNDLE" "$directory/plugins/DataProvider.jar"
    cp "$VELOCITY_CONSUMER" "$directory/plugins/DataProviderAcceptance.jar"
    write_dataprovider_configuration "$directory/plugins/dataprovider" "dataprovider-acceptance" velocity
    mkfifo "$directory/console.in"
    (
        cd "$directory"
        java -Xms256M -Xmx768M -jar "$WORK_DIRECTORY/velocity.jar" <console.in >velocity.log 2>&1
    ) &
    velocity_process=$!
    exec {velocity_input_fd}>"$directory/console.in"
    wait_for_log "$directory/velocity.log" 'DATAPROVIDER_ACCEPTANCE_PASS platform=velocity' 90
    stop_process "$velocity_process" "$velocity_input_fd" end "$directory/velocity.log"
    eval "exec ${velocity_input_fd}>&-"
    velocity_input_fd=""
    velocity_process=""
}

readonly RELEASE_VERSION="$(pom_property revision)"
readonly PAPER_BUNDLE="$ROOT_DIRECTORY/dataprovider-platform-paper/target/dataprovider-platform-paper-${RELEASE_VERSION}-bundled.jar"
readonly VELOCITY_BUNDLE="$ROOT_DIRECTORY/dataprovider-platform-velocity/target/dataprovider-platform-velocity-${RELEASE_VERSION}-bundled.jar"
readonly PAPER_CONSUMER="$ACCEPTANCE_DIRECTORY/consumer-paper/target/dataprovider-acceptance-consumer-paper-${RELEASE_VERSION}.jar"
readonly VELOCITY_CONSUMER="$ACCEPTANCE_DIRECTORY/consumer-velocity/target/dataprovider-acceptance-consumer-velocity-${RELEASE_VERSION}.jar"
readonly PAPER_VERSION="$(pom_property paper.runtime.version)"
readonly PAPER_BUILD="$(pom_property paper.runtime.build)"
readonly VELOCITY_VERSION="$(pom_property velocity.version)"
readonly VELOCITY_BUILD="$(pom_property velocity.runtime.build)"

for artifact in "$PAPER_BUNDLE" "$VELOCITY_BUNDLE" "$PAPER_CONSUMER" "$VELOCITY_CONSUMER"; do
    [[ -f "$artifact" ]] || fail "Missing required release artifact ${artifact}."
done

if jar tf "$PAPER_CONSUMER" | rg -q '^nl/hauntedmc/dataprovider/api/'; then
    fail "Paper consumer bundled DataProvider API classes instead of compiling against the provided API."
fi
if jar tf "$VELOCITY_CONSUMER" | rg -q '^nl/hauntedmc/dataprovider/api/'; then
    fail "Velocity consumer bundled DataProvider API classes instead of compiling against the provided API."
fi

download_runtime paper "$PAPER_VERSION" "$PAPER_BUILD" "$WORK_DIRECTORY/paper.jar"
download_runtime velocity "$VELOCITY_VERSION" "$VELOCITY_BUILD" "$WORK_DIRECTORY/velocity.jar"
docker compose --file "$COMPOSE_FILE" up --detach --wait
wait_for_healthy_backends
start_paper
start_velocity
echo "Platform acceptance passed for Paper ${PAPER_VERSION} build ${PAPER_BUILD} and Velocity ${VELOCITY_VERSION} build ${VELOCITY_BUILD}."
