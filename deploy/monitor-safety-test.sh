#!/usr/bin/env bash
# Local mock regression; no HTTP requests or system resource changes.
set -euo pipefail
SOURCE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/check-health.sh"
temporary="$(mktemp -d /tmp/algohub-monitor-test.XXXXXX)"
[[ "$temporary" == /tmp/algohub-monitor-test.* && ! -L "$temporary" && "$(realpath "$temporary")" == "$temporary" ]] || exit 1
cleanup() { [[ "$temporary" == /tmp/algohub-monitor-test.* ]] && rm -rf -- "$temporary"; }
trap cleanup EXIT
mkdir -p "$temporary/bin" "$temporary/state"
tr -d '\r' < "$SOURCE" > "$temporary/check-health.sh"
export PATH="$temporary/bin:$PATH" ALGOHUB_MONITOR_DIR="$temporary/state" TEST_MONITOR_ROOT="$temporary"
export PUBLIC_ORIGIN=https://site.example.invalid ALERT_WEBHOOK_URL=https://notify.example.invalid

cat > "$temporary/bin/curl" <<'MOCK'
#!/usr/bin/env bash
set -eu
if [[ "$*" == *--data* ]]; then
  echo notify >> "$TEST_MONITOR_ROOT/events"
  [[ "${TEST_WEBHOOK_FAIL:-false}" != true ]]
elif [[ "$*" == *127.0.0.1:8080* ]]; then
  [[ "${TEST_BACKEND_FAIL:-false}" != true ]]
else
  [[ "${TEST_HTTPS_FAIL:-false}" != true ]]
fi
MOCK
cat > "$temporary/bin/df" <<'MOCK'
#!/usr/bin/env bash
printf 'Filesystem 1024-blocks Used Available Capacity Mounted\nmock 1000 500 500 %s%% /\n' "${TEST_DISK_USED:-30}"
MOCK
cat > "$temporary/bin/awk" <<'MOCK'
#!/usr/bin/env bash
if [[ "$*" == *MemAvailable* ]]; then
  echo "${TEST_MEMORY_AVAILABLE:-1048576}"
else
  exec /usr/bin/awk "$@"
fi
MOCK
cat > "$temporary/bin/logger" <<'MOCK'
#!/usr/bin/env bash
echo log >> "$TEST_MONITOR_ROOT/events"
MOCK
chmod 755 "$temporary/bin/"*
passes=0
prepare() {
  rm -f -- "$temporary/state/failed"
  : > "$temporary/events"
  export TEST_BACKEND_FAIL=false TEST_HTTPS_FAIL=false TEST_DISK_USED=30 TEST_MEMORY_AVAILABLE=1048576 TEST_WEBHOOK_FAIL=false
}
run_monitor() { bash "$temporary/check-health.sh" > "$temporary/output" 2>&1; }
check() { "$@" || { cat "$temporary/output" >&2; echo 'ASSERTION FAILED' >&2; exit 1; }; }
pass() { passes=$((passes+1)); printf 'PASS %s\n' "$1"; }

prepare
run_monitor
check test ! -e "$temporary/state/failed"
check test ! -s "$temporary/events"
pass 'healthy backend, HTTPS and resources send no alert'

prepare
export TEST_BACKEND_FAIL=true
if run_monitor; then exit 1; fi
check test -e "$temporary/state/failed"
check test "$(grep -c '^notify$' "$temporary/events")" = 1
if run_monitor; then exit 1; fi
check test "$(grep -c '^notify$' "$temporary/events")" = 1
pass 'persistent backend failure is deduplicated'

export TEST_BACKEND_FAIL=false
run_monitor
check test ! -e "$temporary/state/failed"
export TEST_BACKEND_FAIL=true
if run_monitor; then exit 1; fi
check test "$(grep -c '^notify$' "$temporary/events")" = 2
pass 'recovery clears the marker so a later failure sends a fresh alert'

prepare
export TEST_HTTPS_FAIL=true
if run_monitor; then exit 1; fi
check test "$(grep -c '^notify$' "$temporary/events")" = 1
pass 'public HTTPS failure alerts despite a healthy backend'

prepare
export TEST_DISK_USED=91
if run_monitor; then exit 1; fi
check test "$(grep -c '^notify$' "$temporary/events")" = 1
pass 'disk use above the configured threshold alerts'

prepare
export TEST_MEMORY_AVAILABLE=100000
if run_monitor; then exit 1; fi
check test "$(grep -c '^notify$' "$temporary/events")" = 1
pass 'low available memory alerts'

prepare
export TEST_HTTPS_FAIL=true TEST_WEBHOOK_FAIL=true
if run_monitor; then exit 1; fi
check test ! -e "$temporary/state/failed"
export TEST_WEBHOOK_FAIL=false
if run_monitor; then exit 1; fi
check test -e "$temporary/state/failed"
check test "$(grep -c '^notify$' "$temporary/events")" = 2
pass 'failed delivery does not suppress the next notification attempt'

prepare
export ALGOHUB_MONITOR_DIR=relative-path
if run_monitor; then exit 1; fi
check grep -q 'must exist and be absolute' "$temporary/output"
check test ! -s "$temporary/events"
pass 'test state overrides must use an existing absolute directory'
printf 'Monitor safety regression: %s passed; all curl, df, memory and logger calls were mocked.\n' "$passes"
