#!/usr/bin/env bash
set -euo pipefail

# Stop all API and worker writes before starting this maintenance-window backup.
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ENV_FILE=${ENV_FILE:-"$ROOT/.env"}
DEST=${1:?Usage: scripts/backup_local.sh ABSOLUTE_NEW_BACKUP_DIRECTORY}
[[ "$DEST" = /* && ! -e "$DEST" && -f "$ENV_FILE" ]] || { echo 'Use a new absolute directory and an existing .env' >&2; exit 2; }
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
[[ "${DB_NAME:-}" =~ ^[A-Za-z0-9_]+$ && "${MINIO_BUCKET:-}" =~ ^[a-z0-9][a-z0-9.-]*$ ]] || exit 2
SOURCE_DB=${BACKUP_DB:-$DB_NAME}
SOURCE_BUCKET=${BACKUP_BUCKET:-$MINIO_BUCKET}
[[ "$SOURCE_DB" =~ ^[A-Za-z0-9_]+$ && "$SOURCE_BUCKET" =~ ^[a-z0-9][a-z0-9.-]*$ ]] || exit 2
mkdir -m 700 "$DEST"
cd "$ROOT"
compose=(docker compose --env-file "$ENV_FILE" -f deploy/compose.yml)
"${compose[@]}" exec -T -e SOURCE_DB="$SOURCE_DB" mysql sh -lc 'exec mysqldump -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" --single-transaction --quick --routines --triggers --no-tablespaces "$SOURCE_DB"' > "$DEST/database.sql"
container=$("${compose[@]}" ps -q minio)
[[ -n "$container" ]] || exit 2
object_dir="/tmp/orchard-backup-$RANDOM-$RANDOM"
"${compose[@]}" exec -T -e BACKUP_BUCKET="$SOURCE_BUCKET" -e OBJECT_DIR="$object_dir" minio sh -lc '
  export MC_CONFIG_DIR=$(mktemp -d)
  trap '\''rm -rf "$MC_CONFIG_DIR"'\'' EXIT
  mc alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null
  mc mirror "local/$BACKUP_BUCKET" "$OBJECT_DIR" >/dev/null
'
docker cp "$container:$object_dir" "$DEST/objects"
"${compose[@]}" exec -T -e OBJECT_DIR="$object_dir" minio sh -lc 'rm -rf "$OBJECT_DIR"'
cd "$DEST"
find objects -type f -exec shasum -a 256 {} + > OBJECT_SHA256SUMS
shasum -a 256 database.sql > SHA256SUMS
printf 'database=%s\nbucket=%s\ncreated_utc=%s\n' "$SOURCE_DB" "$SOURCE_BUCKET" "$(date -u +%FT%TZ)" > manifest.txt
chmod 600 database.sql SHA256SUMS OBJECT_SHA256SUMS manifest.txt
echo "Backup created at $DEST (sensitive data; never commit)"
