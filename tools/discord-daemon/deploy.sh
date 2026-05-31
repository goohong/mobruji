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
#      구현: `lib/hook_symlinks.sh` 공통 라이브러리 (setup-*.sh 거울 룰, PR #1124).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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
  # 2026-05-28 (#1085 / PR E-2): helper-launch.sh / helper-role.md symlink 강제.
  helper-launch.sh
  helper-role.md
  # 2026-05-29: helper-tool-progress.sh — PreToolUse hook script.
  # spec: docs/features/helper-tool-visibility.md.
  helper-tool-progress.sh
  # 2026-05-30 (#1360): pr-register-rev.sh — PostToolUse hook script.
  # gh pr create / gh pr merge → rev directive 자동 등록.
  # spec: docs/features/pr-webhook-rev-forum.md (옵션 D, PR 2-a).
  pr-register-rev.sh
  # 2026-05-29: maestro-launch.sh — NCP 본체와 repo SoT 통합 (bootstrap watcher
  # + DISABLE_AUTOUPDATER export). spec: PR fix/maestro-launch-bootstrap-merge.
  maestro-launch.sh
  # 2026-05-29 Phase D — nmae-role.md repo SoT. 자동 위임 폐기 STRICT 룰.
  nmae-role.md
)
DRY_RUN=0

for arg in "$@"; do
  case "$arg" in
    --dry-run|-n)
      DRY_RUN=1
      ;;
    --help|-h)
      sed -n '2,30p' "$0"
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

# hook symlink ensure 공통 라이브러리 source — `log()` / `run()` / `DRY_RUN`
# 정의 후 source 하여 라이브러리가 호출 측 헬퍼를 그대로 재사용한다.
# 함수 본체는 `lib/hook_symlinks.sh` 단일 진실 — deploy / setup-*.sh 거울 룰 (PR #1124).
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib/hook_symlinks.sh"

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
