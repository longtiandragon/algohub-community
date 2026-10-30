#!/usr/bin/env bash
# Uses a mock Docker executable and a temporary directory; never connects to production.
set -euo pipefail
source_script="$(cd "$(dirname "$0")" && pwd)/backup.sh"
fixture="$(mktemp -d /tmp/algohub-backup-test.XXXXXX)"
trap 'rm -rf -- "$fixture"' EXIT
mkdir -p "$fixture/bin" "$fixture/backups"
export BACKUP_FIXTURE="$fixture"
cat > "$fixture/bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
if ! mkdir "$BACKUP_FIXTURE/active" 2>/dev/null; then
  touch "$BACKUP_FIXTURE/overlap"
  exit 1
fi
trap 'rmdir "$BACKUP_FIXTURE/active"' EXIT
printf 'synthetic dump\n'
sleep 0.1
[[ ! -f "$BACKUP_FIXTURE/fail" ]]
MOCK
cat > "$fixture/bin/date" <<'MOCK'
#!/usr/bin/env bash
printf '20261003T000000Z\n'
MOCK
chmod +x "$fixture/bin/"*
export PATH="$fixture/bin:$PATH"
for number in 1 2; do
  bash "$source_script" "$fixture/backups" predeploy > "$fixture/result-$number" &
  pids[$number]=$!
done
wait "${pids[1]}"
wait "${pids[2]}"
[[ ! -f "$fixture/overlap" ]]
echo 'PASS: concurrent backups serialize the dump and retention phases'
archives=("$fixture/backups/"*.dump)
[[ ${#archives[@]} == 2 && "${archives[0]}" != "${archives[1]}" ]]
for archive in "${archives[@]}"; do sha256sum --status -c "$archive.sha256"; done
echo 'PASS: two backups in the same second keep distinct valid archives'
touch "$fixture/fail"
if bash "$source_script" "$fixture/backups" > "$fixture/failed"; then
  echo 'Expected dump failure' >&2; exit 1
fi
archives=("$fixture/backups/"*.dump)
[[ ${#archives[@]} == 2 ]]
[[ -z "$(find "$fixture/backups" -name '*.partial' -print -quit)" ]]
echo 'PASS: failed dump publishes no archive and leaves previous backups intact'
