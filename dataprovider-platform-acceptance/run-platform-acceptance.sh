#!/usr/bin/env bash
set -euo pipefail

ROOT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly ROOT_DIRECTORY
readonly ACCEPTANCE_DIRECTORY="$ROOT_DIRECTORY/dataprovider-platform-acceptance"
readonly COMPOSE_FILE="$ACCEPTANCE_DIRECTORY/docker-compose.yml"
readonly USER_AGENT="DataProvider-release-validation/1.0 (https://github.com/HauntedMC/DataProvider)"
readonly WORK_DIRECTORY="${PLATFORM_ACCEPTANCE_WORK_DIRECTORY:-$(mktemp -d)}"
readonly KEEP_WORK_DIRECTORY="${PLATFORM_ACCEPTANCE_KEEP_WORK_DIRECTORY:-false}"

mkdir -p "$WORK_DIRECTORY"

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
    if [[ "$KEEP_WORK_DIRECTORY" == "true" ]]; then
        echo "Platform acceptance logs retained in $WORK_DIRECTORY" >&2
    else
        rm -rf "$WORK_DIRECTORY"
    fi
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
    value="$(awk -v opening_tag="<${property_name}>" -v closing_tag="</${property_name}>" '
        index($0, opening_tag) {
            value = substr($0, index($0, opening_tag) + length(opening_tag))
            closing_tag_index = index(value, closing_tag)
            if (closing_tag_index > 0) {
                print substr(value, 1, closing_tag_index - 1)
            }
            exit
        }
    ' "$ROOT_DIRECTORY/pom.xml")"
    [[ -n "$value" ]] || fail "Missing Maven property ${property_name}."
    printf '%s' "$value"
}

download_runtime() {
    local project=$1
    local version=$2
    local build=$3
    local expected_checksum=$4
    local destination=$5
    local build_metadata url actual_checksum
    build_metadata="$(curl --fail --silent --show-error --location --retry 3 --retry-all-errors \
        --connect-timeout 15 --max-time 120 --header "User-Agent: $USER_AGENT" \
        "https://fill.papermc.io/v3/projects/${project}/versions/${version}/builds")"
    url="$(jq --raw-output --argjson build "$build" \
        '.[] | select(.id == $build) | .downloads["server:default"].url' <<<"$build_metadata")"
    [[ "$url" != "null" ]] || fail "No ${project} ${version} build ${build} server download exists."
    curl --fail --silent --show-error --location --retry 3 --retry-all-errors --connect-timeout 15 --max-time 120 \
        --output "$destination" "$url"
    actual_checksum="$(sha256sum "$destination" | awk '{print $1}')"
    [[ "$actual_checksum" == "$expected_checksum" ]] || fail "Checksum mismatch for ${project} ${version} build ${build}."
}

backend_port() {
    local service=$1
    local container_port=$2
    local endpoint
    endpoint="$(docker compose --file "$COMPOSE_FILE" port "$service" "$container_port" | head -n 1)"
    [[ "$endpoint" =~ :([0-9]+)$ ]] || fail "Could not determine the host port for ${service}:${container_port}."
    printf '%s' "${BASH_REMATCH[1]}"
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
        if grep -Eq -- "$expected" "$log_file"; then
            return
        fi
        if grep -Eq 'DATAPROVIDER_ACCEPTANCE_FAIL|Exception in thread|Could not load' "$log_file"; then
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
    grep -Eq 'DataProvider disabled' "$log_file" || fail "DataProvider did not report clean platform shutdown."
}

write_dataprovider_configuration() {
    local data_directory=$1
    local owner_plugin=$2
    local connection_identifier=$3
    local mysql_port=$4
    local mongodb_port=$5
    local redis_port=$6
    mkdir -p "$data_directory/databases"
    cp "$ROOT_DIRECTORY/dataprovider-core/src/main/resources/config.yml" "$data_directory/config.yml"
    cat >"$data_directory/databases/mysql.yml" <<EOF
${connection_identifier}:
  access:
    owner_plugin: "${owner_plugin}"
    shared_with: []
  host: 127.0.0.1
  port: ${mysql_port}
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
  port: ${mongodb_port}
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
  port: ${redis_port}
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
  port: ${redis_port}
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
    write_dataprovider_configuration "$directory/plugins/DataProvider" "DataProviderAcceptance" paper \
        "$MYSQL_PORT" "$MONGODB_PORT" "$REDIS_PORT"
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
    write_dataprovider_configuration "$directory/plugins/dataprovider" "dataprovider-acceptance" velocity \
        "$MYSQL_PORT" "$MONGODB_PORT" "$REDIS_PORT"
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

RELEASE_VERSION="$(pom_property revision)"
readonly RELEASE_VERSION
readonly PAPER_BUNDLE="$ROOT_DIRECTORY/dataprovider-platform-paper/target/dataprovider-platform-paper-${RELEASE_VERSION}-bundled.jar"
readonly VELOCITY_BUNDLE="$ROOT_DIRECTORY/dataprovider-platform-velocity/target/dataprovider-platform-velocity-${RELEASE_VERSION}-bundled.jar"
readonly PAPER_CONSUMER="$ACCEPTANCE_DIRECTORY/consumer-paper/target/dataprovider-acceptance-consumer-paper-${RELEASE_VERSION}.jar"
readonly VELOCITY_CONSUMER="$ACCEPTANCE_DIRECTORY/consumer-velocity/target/dataprovider-acceptance-consumer-velocity-${RELEASE_VERSION}.jar"
PAPER_VERSION="$(pom_property paper.runtime.version)"
readonly PAPER_VERSION
PAPER_BUILD="$(pom_property paper.runtime.build)"
readonly PAPER_BUILD
PAPER_SHA256="$(pom_property paper.runtime.sha256)"
readonly PAPER_SHA256
VELOCITY_VERSION="$(pom_property velocity.version)"
readonly VELOCITY_VERSION
VELOCITY_BUILD="$(pom_property velocity.runtime.build)"
readonly VELOCITY_BUILD
VELOCITY_SHA256="$(pom_property velocity.runtime.sha256)"
readonly VELOCITY_SHA256

for artifact in "$PAPER_BUNDLE" "$VELOCITY_BUNDLE" "$PAPER_CONSUMER" "$VELOCITY_CONSUMER"; do
    [[ -f "$artifact" ]] || fail "Missing required release artifact ${artifact}."
done

if jar tf "$PAPER_CONSUMER" | grep -Eq '^nl/hauntedmc/dataprovider/api/'; then
    fail "Paper consumer bundled DataProvider API classes instead of compiling against the provided API."
fi
if jar tf "$VELOCITY_CONSUMER" | grep -Eq '^nl/hauntedmc/dataprovider/api/'; then
    fail "Velocity consumer bundled DataProvider API classes instead of compiling against the provided API."
fi

download_runtime paper "$PAPER_VERSION" "$PAPER_BUILD" "$PAPER_SHA256" "$WORK_DIRECTORY/paper.jar"
download_runtime velocity "$VELOCITY_VERSION" "$VELOCITY_BUILD" "$VELOCITY_SHA256" "$WORK_DIRECTORY/velocity.jar"
docker compose --file "$COMPOSE_FILE" up --detach --wait
wait_for_healthy_backends
MYSQL_PORT="$(backend_port mysql 3306)"
readonly MYSQL_PORT
MONGODB_PORT="$(backend_port mongodb 27017)"
readonly MONGODB_PORT
REDIS_PORT="$(backend_port redis 6379)"
readonly REDIS_PORT
start_paper
start_velocity
echo "Platform acceptance passed for Paper ${PAPER_VERSION} build ${PAPER_BUILD} and Velocity ${VELOCITY_VERSION} build ${VELOCITY_BUILD}."
