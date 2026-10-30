#!/usr/bin/env bash
# 发布与回滚：在服务器上以 root 运行，平时由本地的 scripts/deploy.ps1 上传后调用。
#   release.sh activate <发布包.tar.gz>  先备份、核对迁移指纹再切换；失败仅在 schema 不变时自动回退
#   release.sh rollback [--schema-compatible]  切回上一版应用；默认要求迁移指纹相同，不恢复数据库
#   release.sh status                    当前版本、上一版本、服务状态和最近的备份
# 目录约定：
#   /opt/algohub/releases/<编号>/{algohub.jar,frontend/,deploy/,RELEASE}
#   /opt/algohub/algohub.jar -> releases/<编号>/algohub.jar  （algohub.service 读这个）
#   /opt/algohub/frontend    -> releases/<编号>/frontend     （Nginx 根目录）
# 第一次运行时，把手动部署留下的 algohub.jar 和 frontend/ 收进 releases/ 当作「上一版」。
set -euo pipefail
umask 022

BASE=/opt/algohub
RELEASES=$BASE/releases
BACKUPS=/var/backups/algohub
HEALTH=http://127.0.0.1:8080/internal/health
KEEP=5

log() { printf '[release] %s\n' "$*"; }
die() { printf '[release] 错误：%s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die '需要 root 运行'

lock_release() {
  [[ -d "$BASE" && ! -L "$BASE" ]] || die '应用根目录不存在或为符号链接'
  exec 9>"$BASE/.release.lock"
  flock -n 9 || die '另一个发布或回滚正在运行，请等待其结束'
}

# 迁移版本、脚本与 checksum 的指纹；不把探针成功当成所有 schema 都向后兼容。
schema_fingerprint() {
  local state history
  local compose=(docker compose --env-file /etc/algohub/production.env -f "$BASE/deploy/compose.yml")
  state="$("${compose[@]}" exec -T postgres psql -U algohub -d algohub -XAt -v ON_ERROR_STOP=1 \
    -c "select case when count(*)>0 and bool_and(success) then 'OK' else 'INVALID' end from flyway_schema_history")" || return 1
  [[ "$state" == OK ]] || return 1
  history="$("${compose[@]}" exec -T postgres psql -U algohub -d algohub -XAt -v ON_ERROR_STOP=1 \
    -c "select installed_rank,coalesce(version,''),type,script,coalesce(checksum::text,''),success from flyway_schema_history order by installed_rank")" || return 1
  [[ -n "$history" ]] || return 1
  printf '%s\n' "$history" | sha256sum | cut -d ' ' -f 1
}

supported_schema() {
  local fingerprint
  fingerprint="$(cat "$RELEASES/$1/SCHEMA.sha256" 2>/dev/null)" || return 1
  [[ "$fingerprint" =~ ^[0-9a-f]{64}$ ]] || return 1
  printf '%s\n' "$fingerprint"
}

backup_database() {
  local backup
  backup="$(bash "$BASE/deploy/backup.sh" "$BACKUPS" predeploy </dev/null | sed -n 's/^BACKUP_FILE=//p')" || return 1
  [[ -n "$backup" && -s "$backup" ]] || return 1
  printf '%s\n' "$backup"
}

# 当前指向的发布编号；还是手动部署的普通文件时为空
current_id() {
  local target
  target="$(readlink "$BASE/algohub.jar" 2>/dev/null || true)"
  if [[ "$target" == releases/*/algohub.jar ]]; then
    target="${target#releases/}"
    printf '%s\n' "${target%/algohub.jar}"
  fi
}

previous_id() {
  cat "$RELEASES/.previous" 2>/dev/null || true
}

# 发布目录只给 root 写，服务用户和 Nginx 只读
normalize() {
  chown -R root:root "$1"
  find "$1" -type d -exec chmod 755 {} +
  find "$1" -type f -exec chmod 644 {} +
  find "$1" -type f -name '*.sh' -exec chmod 755 {} +
}

# 用重命名替换软链接，切换是原子的
point_to() {
  local id="$1"
  ln -sfn "releases/$id/algohub.jar" "$BASE/.algohub.jar.next"
  mv -T "$BASE/.algohub.jar.next" "$BASE/algohub.jar"
  ln -sfn "releases/$id/frontend" "$BASE/.frontend.next"
  mv -T "$BASE/.frontend.next" "$BASE/frontend"
}

wait_healthy() {
  local i
  for ((i=0; i<75; i++)); do
    if curl --fail --silent --max-time 3 "$HEALTH" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

# 手动部署的 algohub.jar、frontend/ 收进 releases/legacy-<时间>/
adopt_legacy() {
  [[ -L "$BASE/algohub.jar" ]] && return 0
  [[ -f "$BASE/algohub.jar" && -d "$BASE/frontend" && ! -L "$BASE/frontend" ]] || die "$BASE 的布局和预期不符，先人工检查"
  local id
  id="legacy-$(date -u -r "$BASE/algohub.jar" +%Y%m%dT%H%M%SZ)"
  log "收编手动部署的版本为 $id"
  mkdir -p "$RELEASES/$id"
  mv "$BASE/algohub.jar" "$RELEASES/$id/algohub.jar"
  mv "$BASE/frontend" "$RELEASES/$id/frontend"
  printf 'id=%s\ncommit=unknown\nnote=手动部署的版本，由 release.sh 第一次运行时收编\n' "$id" > "$RELEASES/$id/RELEASE"
  normalize "$RELEASES/$id"
  point_to "$id"
}

prune() {
  local keep_current keep_previous old id
  keep_current="$(current_id)"
  keep_previous="$(previous_id)"
  # 按修改时间从新到旧，留最近 KEEP 个，当前和上一版永远不删
  mapfile -t old < <(ls -1t "$RELEASES" | tail -n +$((KEEP + 1)))
  for id in "${old[@]}"; do
    [[ -n "$id" && "$id" != "$keep_current" && "$id" != "$keep_previous" ]] || continue
    log "删除旧版本 $id"
    rm -rf -- "${RELEASES:?}/$id"
  done
}

activate() {
  local archive="${1:?用法：release.sh activate <发布包.tar.gz>}"
  [[ -f "$archive" ]] || die "找不到发布包 $archive"
  mkdir -p "$RELEASES"
  adopt_legacy
  # 顺手收紧目录权限：只有 root 能改应用文件和定时任务跑的脚本
  chown root:root "$BASE" "$BASE/deploy"
  chmod 755 "$BASE" "$BASE/deploy"
  find "$BASE/deploy" -maxdepth 1 -type f -exec chown root:root {} + -exec chmod 644 {} +
  find "$BASE/deploy" -maxdepth 1 -type f -name '*.sh' -exec chmod 755 {} +

  staging="$RELEASES/.incoming-$$"
  rm -rf "$staging"
  mkdir -p "$staging"
  trap 'rm -rf "$staging"' EXIT
  tar -xzf "$archive" -C "$staging" --no-same-owner
  [[ -f "$staging/RELEASE" && -f "$staging/algohub.jar" && -f "$staging/frontend/index.html" ]] || die '发布包不完整：要有 RELEASE、algohub.jar、frontend/index.html'
  local id
  id="$(sed -n 's/^id=//p' "$staging/RELEASE")"
  [[ "$id" =~ ^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{7,40}$ ]] || die "发布编号不对：$id"
  [[ ! -e "$RELEASES/$id" ]] || die "$id 已经存在"

  local previous old_previous
  previous="$(current_id)"
  old_previous="$(previous_id)"
  local before after expected backup
  before="$(schema_fingerprint)" || die '无法确认 Flyway 成功记录与迁移指纹，没有切换版本'
  log '发布前备份数据库'
  backup="$(backup_database)" || die '数据库备份失败，没有切换版本'
  after="$(schema_fingerprint)" || die '备份后无法读取迁移指纹，没有切换版本'
  [[ "$after" == "$before" ]] || die '备份期间迁移指纹变化，没有切换版本，请核验数据库状态'
  # 首次采用本流程的现运行版本，只在旧探针正常时记录其已观察到的 schema。
  if [[ -n "$previous" && ! -e "$RELEASES/$previous/SCHEMA.sha256" ]] &&
     curl --fail --silent --max-time 3 "$HEALTH" >/dev/null 2>&1; then
    printf '%s\n' "$before" > "$RELEASES/$previous/SCHEMA.sha256"
  fi
  # 保留仍在留存版本里的不可变前端分包，让打开多天的旧标签页继续加载。
  ls -1 "$staging/frontend/assets" > "$staging/frontend/.own-assets" 2>/dev/null || true
  local from name
  for from in "$RELEASES"/*/frontend/assets; do
    [[ -d "$from" ]] || continue
    if [[ -f "${from%/assets}/.own-assets" ]]; then
      while IFS= read -r name; do
        [[ -n "$name" && "$name" != */* && -f "$from/$name" && ! -e "$staging/frontend/assets/$name" ]] || continue
        cp "$from/$name" "$staging/frontend/assets/$name"
      done < "${from%/assets}/.own-assets"
    else
      for name in "$from"/*; do
        [[ -f "$name" && ! -e "$staging/frontend/assets/${name##*/}" ]] || continue
        cp "$name" "$staging/frontend/assets/"
      done
    fi
  done
  mv "$staging" "$RELEASES/$id"
  trap - EXIT
  normalize "$RELEASES/$id"
  printf '%s\n' "$before" > "$RELEASES/$id/SCHEMA.before.sha256"

  # 服务器上跑的脚本（备份、健康检查、恢复演练、本脚本）跟着发布版本走
  local script
  for script in backup.sh check-health.sh restore-check.sh release.sh compose.yml; do
    if [[ -f "$RELEASES/$id/deploy/$script" ]]; then
      install -m "$([[ $script == *.sh ]] && echo 755 || echo 644)" -o root -g root \
        "$RELEASES/$id/deploy/$script" "$BASE/deploy/.$script.next"
      mv -f "$BASE/deploy/.$script.next" "$BASE/deploy/$script"
    fi
  done

  log "备份完成：$backup"

  printf '%s\n' "$previous" > "$RELEASES/.previous"
  log "切换：${previous:-（无）} -> $id"
  point_to "$id"
  if systemctl restart algohub && wait_healthy; then
    if after="$(schema_fingerprint)"; then
      printf '%s\n' "$after" > "$RELEASES/$id/SCHEMA.sha256"
      log "健康检查通过，已记录本版本支持的迁移指纹"
      prune
      printf 'RELEASE_OK id=%s previous=%s backup=%s\n' "$id" "$previous" "$backup"
      return 0
    fi
    log '健康探针通过，但无法确认迁移指纹；发布仍需人工核验'
  fi

  log '新版本未通过健康或迁移检查，核验是否可以安全回退'
  journalctl -u algohub -n 60 --no-pager || true
  printf '%s\n' "$id" > "$RELEASES/.failed"
  if ! after="$(schema_fingerprint)" || [[ "$after" != "$before" ]] ||
     [[ -z "$previous" ]] || ! expected="$(supported_schema "$previous")" || [[ "$expected" != "$after" ]]; then
    log '迁移已变化或兼容指纹未知：没有自动切回旧二进制。请人工验证 schema 兼容性；数据库未恢复。'
    printf 'RELEASE_FAILED id=%s previous=%s backup=%s rollback=BLOCKED_SCHEMA\n' "$id" "$previous" "$backup"
    return 1
  fi
  if [[ -n "$previous" ]]; then
    point_to "$previous"
    printf '%s\n' "$old_previous" > "$RELEASES/.previous"
    if systemctl restart algohub && wait_healthy; then
      log "已切回 $previous，服务正常"
    else
      log '切回后服务启动或健康检查失败，需要人工处理'
      printf 'RELEASE_FAILED id=%s previous=%s backup=%s rollback=FAILED_HEALTH\n' "$id" "$previous" "$backup"
      return 1
    fi
  fi
  printf 'RELEASE_FAILED id=%s previous=%s backup=%s\n' "$id" "$previous" "$backup"
  return 1
}

rollback() {
  local current previous before expected backup override=false
  [[ $# -eq 0 || ($# -eq 1 && "$1" == --schema-compatible) ]] || die '回滚参数只能是 --schema-compatible'
  [[ $# -eq 0 ]] || override=true
  current="$(current_id)"
  previous="$(previous_id)"
  [[ -n "$previous" && -d "$RELEASES/$previous" ]] || die '没有可以切回的上一版'
  [[ "$previous" != "$current" ]] || die "上一版就是当前版本 $current"
  if ! before="$(schema_fingerprint)" || ! expected="$(supported_schema "$previous")" || [[ "$before" != "$expected" ]]; then
    [[ "$override" == true ]] || die '迁移指纹未知或与目标版本不同，拒绝切回；人工确认兼容后可显式加 --schema-compatible，数据库不会恢复'
  fi
  backup="$(backup_database)" || die '回滚前数据库备份失败，没有切换版本'
  if [[ "$override" == true ]]; then
    log '运维人员显式声明 --schema-compatible；仅切换应用，数据库不恢复，请确认已核验兼容性'
  fi
  printf '%s rollback current=%s target=%s schema_compatible_override=%s backup=%s\n' \
    "$(date -u +%FT%TZ)" "$current" "$previous" "$override" "$backup" >> "$BASE/release-audit.log"
  log "切回：$current -> $previous（数据库不恢复）"
  printf '%s\n' "$current" > "$RELEASES/.previous"
  point_to "$previous"
  systemctl restart algohub || die '已切换应用版本，但服务启动失败，看 journalctl -u algohub；数据库未恢复'
  wait_healthy || die '切回后健康检查没通过，看 journalctl -u algohub'
  printf 'ROLLBACK_OK id=%s previous=%s\n' "$previous" "$current"
}

status() {
  local current
  current="$(current_id)"
  if [[ -n "$current" ]]; then
    printf 'current=%s\n' "$current"
    sed 's/^/current./' "$RELEASES/$current/RELEASE"
  else
    printf 'current=manual\n'
  fi
  printf 'previous=%s\n' "$(previous_id)"
  printf 'service=%s\n' "$(systemctl is-active algohub || true)"
  printf 'health=%s\n' "$(curl --silent --max-time 3 "$HEALTH" || echo unreachable)"
  printf 'latest_backup=%s\n' "$(ls -1t "$BACKUPS"/*.dump 2>/dev/null | head -n 1)"
}

case "${1:-}" in
  activate) shift; lock_release; activate "$@" ;;
  rollback) shift; lock_release; rollback "$@" ;;
  status) status ;;
  *) die '用法：release.sh activate <发布包.tar.gz> | rollback [--schema-compatible] | status' ;;
esac
