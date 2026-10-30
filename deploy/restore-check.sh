#!/usr/bin/env bash
set -euo pipefail
umask 077
archive="$(realpath "${1:?Usage: restore-check.sh backup.dump}")"
[[ -f "$archive" ]] || exit 1
if [[ -f "$archive.sha256" ]]; then
  expected="$(cut -d ' ' -f 1 "$archive.sha256")"
  actual="$(sha256sum "$archive")"
  [[ "$expected" =~ ^[[:xdigit:]]{64}$ && "${actual%% *}" == "$expected" ]] || { echo 'Backup checksum mismatch' >&2; exit 1; }
fi
database="algohub_restore_$(date -u +%Y%m%d%H%M%S)_$$"
[[ "$database" =~ ^algohub_restore_[0-9]+_[0-9]+$ ]] || exit 1
compose=(docker compose --env-file /etc/algohub/production.env -f /opt/algohub/deploy/compose.yml)
"${compose[@]}" exec -T postgres createdb -U algohub "$database"
cleanup() { "${compose[@]}" exec -T postgres dropdb -U algohub --if-exists "$database"; }
trap cleanup EXIT
"${compose[@]}" exec -T postgres pg_restore -U algohub --exit-on-error --no-owner -d "$database" < "$archive"
"${compose[@]}" exec -T postgres psql -U algohub -d "$database" -v ON_ERROR_STOP=1 \
  -c 'select count(*) as users from app_user; select count(*) as accounts from platform_account; select count(*) as migrations from flyway_schema_history;'
echo 'Restore succeeded in an isolated temporary database. Original database was not modified.'
