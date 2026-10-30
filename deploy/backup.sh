#!/usr/bin/env bash
# 备份数据库：backup.sh [目录] [标签]。每天由 algohub-backup.timer 运行；发布前 release.sh 带标签 predeploy 再跑一次。
# 保留最近 30 天，且不论多旧至少留最新 14 份；最后一行输出 BACKUP_FILE=<路径> 给调用方读取。
set -euo pipefail
umask 077
destination="${1:-/var/backups/algohub}"
label="${2:-}"
[[ -z "$label" || "$label" =~ ^[a-z0-9-]+$ ]] || { echo "标签只能是小写字母、数字和连字符：$label" >&2; exit 1; }
mkdir -p "$destination"
# Timer, manual and release backups share this lock, including retention cleanup.
exec 8>"$destination/.backup.lock"
flock -x 8
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
partial="$(mktemp --suffix=.dump.partial "$destination/algohub-$stamp${label:+-$label}-XXXXXX")"
archive="${partial%.partial}"
trap 'rm -f -- "$partial" "$archive.sha256.partial"' EXIT
docker compose --env-file /etc/algohub/production.env -f /opt/algohub/deploy/compose.yml \
  exec -T postgres pg_dump -U algohub -d algohub -Fc < /dev/null > "$partial"
mv "$partial" "$archive"
sha256sum "$archive" > "$archive.sha256.partial"
mv "$archive.sha256.partial" "$archive.sha256"

# 清理：超过 30 天、且不在最新 14 份里的删掉（连同 .sha256）
mapfile -t dumps < <(ls -1t "$destination"/algohub-*.dump 2>/dev/null)
for old in "${dumps[@]:14}"; do
  if [[ -n "$(find "$old" -maxdepth 0 -mtime +30)" ]]; then
    rm -f -- "$old" "$old.sha256"
  fi
done

echo "Database backup: $archive; copy encrypted off-host. Back up /etc/algohub/secrets separately."
echo "BACKUP_FILE=$archive"
