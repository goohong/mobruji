#!/usr/bin/env bash
# bridge 배포 dir 갱신 + 봇 재시작 helper (PR #1126 spec).
#
# 전용 dir (`MOBRUJI_BRIDGE_DEPLOY_DIR`, default `/home/mobruji/mobruji-bridge`)
# 에서 `git fetch origin develop && git reset --hard origin/develop` 후
# `mobruji-discord-bridge.service` 를 재시작합니다.
#
# spec: docs/features/bridge-deployment-dir-separation.md
#
# 사용법:
#   sudo /home/mobruji/mobruji-bridge/tools/discord-daemon/deploy.sh
#   sudo /home/mobruji/mobruji-bridge/tools/discord-daemon/deploy.sh --dry-run
#
# 환경변수 (선택):
#   MOBRUJI_BRIDGE_DEPLOY_DIR — 전용 dir 경로 (default /home/mobruji/mobruji-bridge)
#   MOBRUJI_BRIDGE_SERVICE — systemd unit 이름 (default mobruji-discord-bridge)
#   MOBRUJI_BRIDGE_BRANCH — git 브랜치 (default develop)
#   MOBRUJI_HOOK_LINK_DIR — hook symlink target dir (default /home/mobruji/.mobruji)
#   MOBRUJI_HOOK_SOURCE_DIR — repo 내 hook source dir
#       (default /home/mobruji/mobruji/tools/discord-daemon — repo root 의 main 워크트리)
#   MOBRUJI_HOOK_SYMLINK_ENSURE — 1 (default) / 0 (skip). 0 시 hook symlink ensure 단계 skip.
#
# 안전 가드:
#   1. 전용 dir 의 .git 디렉토리 존재 검증 (잘못된 dir 실수 방지).
#   2. 현재 브랜치 == 기대 브랜치 검증 (다른 브랜치 reset 사고 방지).
#   3. dry-run 옵션 — 실제 git/systemctl/symlink 명령 echo 만.
#   4. systemctl 재시작 후 journalctl 마지막 30줄 출력 (사용자 즉시 확인).
#   5. hook symlink ensure — `~/.mobruji/<hook>` 가 regular file 이면 backup 후 symlink
#      로 전환 (PR #1154 helper-direct-work-guard 머지 후 수동 절차 자동화).

set -euo pipefail

DEPLOY_DIR=${MOBRUJI_BRIDGE_DEPLOY_DIR:-/home/mobruji/mobruji-bridge}
SERVICE=${MOBRUJI_BRIDGE_SERVICE:-mobruji-discord-bridge}
BRANCH=${MOBRUJI_BRIDGE_BRANCH:-develop}
HOOK_LINK_DIR=${MOBRUJI_HOOK_LINK_DIR:-/home/mobruji/.mobruji}
HOOK_SOURCE_DIR=${MOBRUJI_HOOK_SOURCE_DIR:-/home/mobruji/mobruji/tools/discord-daemon}
HOOK_SYMLINK_ENSURE=${MOBRUJI_HOOK_SYMLINK_ENSURE:-1}
HOOK_NAMES=(
  helper-direct-work-guard.sh
  helper-turn-start.sh
  discord-reply.sh
  nmae-discord-push.sh
)
DRY_RUN=0

for arg in "$@"; do
  case "$arg" in
    --dry-run|-n)
      DRY_RUN=1
      ;;
    --help|-h)
      sed -n '2,28p' "$0"
      exit 0
      ;;
    *)
      echo "deploy.sh: 알 수 없는 옵션: $arg" >&2
      exit 1
      ;;
  esac
done

log() {
  printf '[deploy] %s\n' "$*"
}

run() {
  if [[ "$DRY_RUN" == "1" ]]; then
    printf '[dry-run] %s\n' "$*"
  else
    eval "$@"
  fi
}

# hook 단일 파일 symlink ensure.
#
# 인자: $1 = hook 파일명 (예: helper-direct-work-guard.sh)
#
# 동작:
#   - source 파일 (HOOK_SOURCE_DIR/<name>) 부재 시 skip + log (PR 머지 전 hook 호환).
#   - link 위치 (HOOK_LINK_DIR/<name>) 가 이미 source 와 동일한 symlink → skip ("ok").
#   - link 위치가 regular file → 같은 dir 에 backup (`.bak.<TS>`) 후 symlink 생성.
#   - link 위치가 다른 target 의 symlink 또는 broken → unlink 후 symlink 재생성.
#   - link 위치 부재 → symlink 신규 생성.
#
# DRY_RUN=1 시 실제 명령 echo 만.
ensure_hook_symlink() {
  local hook_name=$1
  local source_path="$HOOK_SOURCE_DIR/$hook_name"
  local link_path="$HOOK_LINK_DIR/$hook_name"

  if [[ ! -f "$source_path" ]]; then
    log "  $hook_name: source 부재 ($source_path) — skip."
    return 0
  fi

  if [[ -L "$link_path" ]]; then
    local current_target
    current_target=$(readlink "$link_path")
    if [[ "$current_target" == "$source_path" ]]; then
      if [[ -e "$link_path" ]]; then
        log "  $hook_name: 이미 올바른 symlink — skip."
        return 0
      else
        log "  $hook_name: symlink 깨짐 ($current_target) — 재생성."
      fi
    else
      log "  $hook_name: 다른 target ($current_target) — 재생성."
    fi
    run "rm -f \"$link_path\""
    run "ln -s \"$source_path\" \"$link_path\""
    return 0
  fi

  if [[ -e "$link_path" ]]; then
    local backup_path
    backup_path="${link_path}.bak.$(date +%Y%m%d%H%M%S)"
    log "  $hook_name: regular file 감지 — $backup_path 로 backup 후 symlink 전환."
    run "mv \"$link_path\" \"$backup_path\""
    run "ln -s \"$source_path\" \"$link_path\""
    return 0
  fi

  log "  $hook_name: link 부재 — symlink 신규 생성."
  run "ln -s \"$source_path\" \"$link_path\""
}

ensure_hook_symlinks() {
  if [[ "$HOOK_SYMLINK_ENSURE" != "1" ]]; then
    log "HOOK_SYMLINK_ENSURE=0 — hook symlink ensure skip."
    return 0
  fi

  if [[ ! -d "$HOOK_LINK_DIR" ]]; then
    log "HOOK_LINK_DIR=$HOOK_LINK_DIR 부재 — hook symlink ensure skip (사용자 환경 기본 dir 없음)."
    return 0
  fi

  log "hook symlink ensure (HOOK_SOURCE_DIR=$HOOK_SOURCE_DIR, HOOK_LINK_DIR=$HOOK_LINK_DIR)"
  for hook_name in "${HOOK_NAMES[@]}"; do
    ensure_hook_symlink "$hook_name"
  done
}

# 1. 전용 dir 존재 + .git 디렉토리 검증
if [[ ! -d "$DEPLOY_DIR" ]]; then
  echo "deploy.sh: DEPLOY_DIR=$DEPLOY_DIR 가 존재하지 않습니다. spec §5-1 절차로 clone 하십시오." >&2
  exit 2
fi
if [[ ! -d "$DEPLOY_DIR/.git" ]]; then
  echo "deploy.sh: DEPLOY_DIR=$DEPLOY_DIR 에 .git 디렉토리가 없습니다 — git clone 되지 않은 디렉토리입니다." >&2
  exit 2
fi

cd "$DEPLOY_DIR"

# 2. 현재 브랜치 검증
CURRENT_BRANCH=$(git branch --show-current)
if [[ "$CURRENT_BRANCH" != "$BRANCH" ]]; then
  echo "deploy.sh: 현재 브랜치=$CURRENT_BRANCH, 기대=$BRANCH. 전용 dir 은 항상 $BRANCH 에 fixed 이어야 합니다 (spec §3 운영 룰)." >&2
  echo "  강제 전환 필요 시: cd $DEPLOY_DIR && git checkout $BRANCH" >&2
  exit 2
fi

log "DEPLOY_DIR=$DEPLOY_DIR SERVICE=$SERVICE BRANCH=$BRANCH DRY_RUN=$DRY_RUN"

# 3. git fetch + reset
log "git fetch origin $BRANCH"
run "git fetch origin $BRANCH"

PREV_SHA=$(git rev-parse HEAD)
log "현재 HEAD: $PREV_SHA"

log "git reset --hard origin/$BRANCH"
run "git reset --hard origin/$BRANCH"

NEW_SHA=$(git rev-parse HEAD)
log "새 HEAD: $NEW_SHA"

if [[ "$PREV_SHA" == "$NEW_SHA" && "$DRY_RUN" == "0" ]]; then
  log "HEAD 변화 없음 — 봇 재시작 skip."
  exit 0
fi

# 4. hook symlink ensure (deploy 마다 idempotent — repo 내 hook source 가 진실)
ensure_hook_symlinks

# 5. systemctl restart + journal 확인
log "sudo systemctl restart $SERVICE"
run "sudo systemctl restart $SERVICE"

if [[ "$DRY_RUN" == "0" ]]; then
  # 봇 boot 까지 약간 대기 후 journal 확인 — 사용자 즉시 가시화.
  sleep 3
  log "journalctl -u $SERVICE -n 30 --no-pager"
  sudo journalctl -u "$SERVICE" -n 30 --no-pager || true
fi

log "deploy 완료. WorkingDirectory 검증: sudo systemctl show $SERVICE -p WorkingDirectory"
