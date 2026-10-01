#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENV_FILE=${ENV_FILE:-"$ROOT/.env"}
SOURCE=${1:?Usage: scripts/restore_local_test.sh ABSOLUTE_BACKUP_DIRECTORY NEW_TEST_DATABASE NEW_TEST_BUCKET}
TEST_DB=${2:?Missing new test database}
TEST_BUCKET=${3:?Missing new test bucket}
[[ "$SOURCE" = /* && -d "$SOURCE" && "$TEST_DB" =~ ^orchard_mall_restore_[A-Za-z0-9_]+$ && "$TEST_BUCKET" =~ ^orchard-restore-[a-z0-9-]+$ && -f "$ENV_FILE" ]] || { echo 'Use isolated orchard restore names and a valid backup path' >&2; exit 2; }
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
[[ "$TEST_DB" != "$DB_NAME" && "$TEST_BUCKET" != "$MINIO_BUCKET" ]] || exit 2
cd "$SOURCE"
shasum -a 256 -c SHA256SUMS
shasum -a 256 -c OBJECT_SHA256SUMS
cd "$ROOT"
compose=(docker compose --env-file "$ENV_FILE" -f deploy/compose.yml)
exists=$("${compose[@]}" exec -T -e RESTORE_DB="$TEST_DB" mysql sh -lc 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" -Nse "SHOW DATABASES LIKE '\''$RESTORE_DB'\''"' 2>/dev/null)
[[ -z "$exists" ]] || { echo 'Test database already exists; refusing overwrite' >&2; exit 3; }
"${compose[@]}" exec -T -e RESTORE_BUCKET="$TEST_BUCKET" minio sh -lc '
  export MC_CONFIG_DIR=$(mktemp -d)
  trap '\''rm -rf "$MC_CONFIG_DIR"'\'' EXIT
  mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
  ! mc ls "local/$RESTORE_BUCKET" >/dev/null 2>&1
' || { echo 'Test bucket already exists; refusing overwrite' >&2; exit 3; }
"${compose[@]}" exec -T -e RESTORE_DB="$TEST_DB" mysql sh -lc 'mysql -u root -p"$MYSQL_ROOT_PASSWORD" -e "CREATE DATABASE \`$RESTORE_DB\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT ALL ON \`$RESTORE_DB\`.* TO '\''$MYSQL_USER'\''@'\''%'\''"' 2>/dev/null
"${compose[@]}" exec -T -e RESTORE_DB="$TEST_DB" mysql sh -lc 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$RESTORE_DB"' 2>/dev/null < "$SOURCE/database.sql"
container=$("${compose[@]}" ps -q minio)
[[ -n "$container" ]] || exit 2
object_dir="/tmp/orchard-restore-$RANDOM-$RANDOM"
docker cp "$SOURCE/objects" "$container:$object_dir"
"${compose[@]}" exec -T -e RESTORE_BUCKET="$TEST_BUCKET" -e OBJECT_DIR="$object_dir" minio sh -lc '
  export MC_CONFIG_DIR=$(mktemp -d)
  trap '\''rm -rf "$MC_CONFIG_DIR" "$OBJECT_DIR"'\'' EXIT
  mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
  mc mb "local/$RESTORE_BUCKET" >/dev/null
  mc mirror "$OBJECT_DIR" "local/$RESTORE_BUCKET" >/dev/null
'
echo "Restored to $TEST_DB and $TEST_BUCKET. Keep restored workers and external adapters disabled."
