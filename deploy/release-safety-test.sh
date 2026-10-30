#!/usr/bin/env bash
# Local isolated regression: bash deploy/release-safety-test.sh (Linux/WSL root).
# Docker, HTTP and systemd are mocked; only a validated /tmp directory is written.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo 'Run this isolated harness as root in local Linux/WSL' >&2; exit 1; }
SOURCE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/release.sh"
temporary="$(mktemp -d /tmp/algohub-release-test.XXXXXX)"
[[ "$temporary" == /tmp/algohub-release-test.* && ! -L "$temporary" &&
   "$(realpath "$temporary")" == "$temporary" && "$(stat -c %u "$temporary")" == 0 ]] || exit 1
cleanup() { [[ "$temporary" == /tmp/algohub-release-test.* ]] && rm -rf -- "$temporary"; }
trap cleanup EXIT
mkdir -p "$temporary/bin"
export PATH="$temporary/bin:$PATH"
export TEST_RELEASE_ROOT="$temporary"

cat > "$temporary/bin/docker" <<'MOCK'
#!/usr/bin/env bash
set -eu
[[ "$(cat "$TEST_RELEASE_ROOT/schema")" != unknown ]] || exit 1
if [[ "$*" == *bool_and* ]]; then
  if [[ "$(cat "$TEST_RELEASE_ROOT/schema")" == invalid ]]; then echo INVALID; else echo OK; fi
else
  echo '1|1|SQL|V1__init.sql|111|t'
  [[ "$(cat "$TEST_RELEASE_ROOT/schema")" != 2 ]] || echo '2|2|SQL|V2__change.sql|222|t'
fi
MOCK
cat > "$temporary/bin/systemctl" <<'MOCK'
#!/usr/bin/env bash
set -eu
if [[ "$1" == restart ]]; then
  target="$(readlink "$TEST_RELEASE_ROOT/base/algohub.jar")"
  echo "restart:$target" >> "$TEST_RELEASE_ROOT/events"
  if [[ "$target" == *bbbbbbb* ]]; then
    case "${TEST_MIGRATE:-none}" in
      changed) echo 2 > "$TEST_RELEASE_ROOT/schema" ;;
      unknown) echo unknown > "$TEST_RELEASE_ROOT/schema" ;;
    esac
    [[ "${TEST_NEW_RESTART:-ok}" == ok ]] || exit 1
  else
    [[ "${TEST_OLD_RESTART:-ok}" == ok ]] || exit 1
  fi
else
  echo active
fi
MOCK
cat > "$temporary/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -eu
target="$(readlink "$TEST_RELEASE_ROOT/base/algohub.jar")"
[[ "$target" != *bbbbbbb* || "${TEST_NEW_HEALTH:-ok}" == ok ]]
MOCK
cat > "$temporary/bin/journalctl" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK
cat > "$temporary/bin/sleep" <<'MOCK'
#!/usr/bin/env bash
exit 0
MOCK
chmod 755 "$temporary/bin/"*

old=20261002T100000Z-aaaaaaa
new=20261002T110000Z-bbbbbbb
fingerprint="$(printf '1|1|SQL|V1__init.sql|111|t\n' | sha256sum | cut -d ' ' -f 1)"
passes=0
prepare() {
  # Every recursive target is below the previously verified temporary root.
  rm -rf -- "$temporary/base" "$temporary/stage" "$temporary/backups"
  mkdir -p "$temporary/base/releases/$old/frontend/assets" "$temporary/base/deploy" "$temporary/stage/frontend/assets" "$temporary/stage/deploy" "$temporary/backups"
  printf old > "$temporary/base/releases/$old/algohub.jar"
  printf old > "$temporary/base/releases/$old/frontend/index.html"
  printf 'id=%s\n' "$old" > "$temporary/base/releases/$old/RELEASE"
  ln -s "releases/$old/algohub.jar" "$temporary/base/algohub.jar"
  ln -s "releases/$old/frontend" "$temporary/base/frontend"
  printf 'id=%s\n' "$new" > "$temporary/stage/RELEASE"
  printf new > "$temporary/stage/algohub.jar"
  printf new > "$temporary/stage/frontend/index.html"
  cat > "$temporary/base/deploy/backup.sh" <<'MOCK'
#!/usr/bin/env bash
set -eu
echo backup >> "$TEST_RELEASE_ROOT/events"
mkdir -p "$1"
printf database > "$1/isolated.dump"
echo "BACKUP_FILE=$1/isolated.dump"
MOCK
  cp "$temporary/base/deploy/backup.sh" "$temporary/stage/deploy/backup.sh"
  : > "$temporary/base/deploy/compose.yml"
  # Test a copy with exactly two fixed directory constants rewritten. No production hook exists.
  [[ "$(tr -d '\r' < "$SOURCE" | grep -c '^BASE=/opt/algohub$')" == 1 ]]
  [[ "$(tr -d '\r' < "$SOURCE" | grep -c '^BACKUPS=/var/backups/algohub$')" == 1 ]]
  tr -d '\r' < "$SOURCE" | sed "s|^BASE=/opt/algohub$|BASE=$temporary/base|;s|^BACKUPS=/var/backups/algohub$|BACKUPS=$temporary/backups|" > "$temporary/release.sh"
  tar -czf "$temporary/package.tar.gz" -C "$temporary/stage" .
  echo 1 > "$temporary/schema"
  : > "$temporary/events"
  export TEST_NEW_HEALTH=ok TEST_MIGRATE=none TEST_NEW_RESTART=ok TEST_OLD_RESTART=ok
}
check() { "$@" || { cat "$temporary/output" >&2; echo 'ASSERTION FAILED' >&2; exit 1; }; }
pass() { passes=$((passes+1)); printf 'PASS %s\n' "$1"; }
run_release() { bash "$temporary/release.sh" "$@" > "$temporary/output" 2>&1; }
is_current() { [[ "$(readlink "$temporary/base/algohub.jar")" == "releases/$1/algohub.jar" ]]; }

prepare
run_release activate "$temporary/package.tar.gz"
check is_current "$new"
check test "$(cat "$temporary/base/releases/$new/SCHEMA.sha256")" = "$fingerprint"
check test "$(head -n 1 "$temporary/events")" = backup
pass 'healthy activation records the supported schema after the backup'

prepare
for stale in {1..10}; do
  mkdir -p "$temporary/base/releases/stale-$stale"
  touch -t 202001010000 "$temporary/base/releases/stale-$stale"
done
run_release activate "$temporary/package.tar.gz"
check is_current "$new"
check grep -q '删除旧版本 stale-' "$temporary/output"
check grep -q "^RELEASE_OK id=$new " "$temporary/output"
pass 'pruning old releases preserves the activated release identifier'

prepare
export TEST_NEW_HEALTH=fail
if run_release activate "$temporary/package.tar.gz"; then exit 1; fi
check is_current "$old"
check grep -q "restart:releases/$old/algohub.jar" "$temporary/events"
pass 'unchanged schema permits automatic application rollback'

prepare
export TEST_NEW_RESTART=fail
if run_release activate "$temporary/package.tar.gz"; then exit 1; fi
check is_current "$old"
check grep -q 'RELEASE_FAILED' "$temporary/output"
check grep -q '已切回' "$temporary/output"
pass 'failed new service restart enters the unchanged-schema recovery branch'

prepare
export TEST_NEW_RESTART=fail TEST_MIGRATE=changed
if run_release activate "$temporary/package.tar.gz"; then exit 1; fi
check is_current "$new"
check grep -q 'rollback=BLOCKED_SCHEMA' "$temporary/output"
check test "$(grep -c '^restart:' "$temporary/events")" = 1
pass 'failed restart after a migration still blocks automatic rollback'

prepare
export TEST_NEW_RESTART=fail TEST_OLD_RESTART=fail
if run_release activate "$temporary/package.tar.gz"; then exit 1; fi
check is_current "$old"
check grep -q 'rollback=FAILED_HEALTH' "$temporary/output"
pass 'failed recovery restart reports a structured release failure'

prepare
export TEST_NEW_HEALTH=fail TEST_MIGRATE=changed
if run_release activate "$temporary/package.tar.gz"; then exit 1; fi
check is_current "$new"
check grep -q 'rollback=BLOCKED_SCHEMA' "$temporary/output"
check test "$(grep -c '^restart:' "$temporary/events")" = 1
pass 'migration changes block automatic rollback to an old binary'

prepare
export TEST_NEW_HEALTH=fail TEST_MIGRATE=unknown
if run_release activate "$temporary/package.tar.gz"; then exit 1; fi
check is_current "$new"
check grep -q 'rollback=BLOCKED_SCHEMA' "$temporary/output"
pass 'unreadable migration state blocks automatic rollback'

prepare
echo invalid > "$temporary/schema"
if run_release activate "$temporary/package.tar.gz"; then exit 1; fi
check is_current "$old"
check test ! -s "$temporary/events"
pass 'failed Flyway records prevent activation before any switch'

prepare
mkdir -p "$temporary/base/releases/$new/frontend"
printf new > "$temporary/base/releases/$new/algohub.jar"
printf '%s\n' "$old" > "$temporary/base/releases/.previous"
ln -sfn "releases/$new/algohub.jar" "$temporary/base/algohub.jar"
if run_release rollback; then exit 1; fi
check is_current "$new"
pass 'manual rollback refuses unknown target compatibility'

printf '%s\n' "$fingerprint" > "$temporary/base/releases/$old/SCHEMA.sha256"
echo 2 > "$temporary/schema"
if run_release rollback; then exit 1; fi
check is_current "$new"
pass 'manual rollback refuses a different supported schema'

run_release rollback --schema-compatible
check is_current "$old"
check grep -q 'schema_compatible_override=true' "$temporary/base/release-audit.log"
check test "$(head -n 1 "$temporary/events")" = backup
pass 'explicit compatibility override backs up and records the operator declaration'

prepare
mkdir -p "$temporary/base/releases/$new/frontend"
printf new > "$temporary/base/releases/$new/algohub.jar"
printf '%s\n' "$old" > "$temporary/base/releases/.previous"
printf '%s\n' "$fingerprint" > "$temporary/base/releases/$old/SCHEMA.sha256"
ln -sfn "releases/$new/algohub.jar" "$temporary/base/algohub.jar"
export TEST_OLD_RESTART=fail
if run_release rollback; then exit 1; fi
check is_current "$old"
check grep -q '服务启动失败' "$temporary/output"
pass 'manual rollback reports a clear service restart failure after the switch'

prepare
exec 8>"$temporary/base/.release.lock"
flock -n 8
if run_release activate "$temporary/package.tar.gz"; then exit 1; fi
check is_current "$old"
check test ! -s "$temporary/events"
if run_release rollback; then exit 1; fi
check grep -q '另一个发布或回滚正在运行' "$temporary/output"
flock -u 8
pass 'both activation and rollback refuse a competing workflow lock'
printf 'Release safety regression: %s passed; no real Docker, HTTP or systemd calls.\n' "$passes"
