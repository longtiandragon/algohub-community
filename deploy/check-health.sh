#!/usr/bin/env bash
set -euo pipefail
monitor_dir="${ALGOHUB_MONITOR_DIR:-/run/algohub-monitor}"
[[ "$monitor_dir" == /* && -d "$monitor_dir" ]] || { echo 'Monitor state directory must exist and be absolute' >&2; exit 1; }
healthy=true
curl --fail --silent --max-time 15 http://127.0.0.1:8080/internal/health >/dev/null || healthy=false
# 同机探针无法发现整台主机离线，仍需配置站外探针。
if [[ -n "${PUBLIC_ORIGIN:-}" ]]; then
  curl --fail --silent --max-time 15 "${PUBLIC_ORIGIN%/}/" >/dev/null || healthy=false
fi
disk_used="$(df -P / | awk 'NR==2 {gsub(/%/, "", $5); print $5}')"
[[ "$disk_used" =~ ^[0-9]+$ && "$disk_used" -lt "${DISK_ALERT_PERCENT:-90}" ]] || healthy=false
memory_available="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)"
[[ "$memory_available" =~ ^[0-9]+$ && "$memory_available" -ge "${MEMORY_ALERT_KB:-262144}" ]] || healthy=false
if [[ "$healthy" == true ]]; then
  rm -f "$monitor_dir/failed"
  exit 0
fi
logger -p daemon.err -t algohub 'Backend, public HTTPS or host resource health check failed'
if [[ ! -e "$monitor_dir/failed" ]]; then
  if [[ -n "${ALERT_WEBHOOK_URL:-}" ]]; then
    # 故障状态持续时去重；发送失败下次重试。
    curl --fail --silent --max-time 15 -H 'Content-Type: application/json' \
      --data '{"text":"AlgoHub backend, public HTTPS or resource health check failed. Inspect service, disk and memory."}' \
      "$ALERT_WEBHOOK_URL" >/dev/null
  fi
  touch "$monitor_dir/failed"
fi
exit 1
