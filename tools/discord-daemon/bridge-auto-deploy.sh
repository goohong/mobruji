#!/usr/bin/env bash
# mobruji-bridge 자동 배포 — develop 최신 동기 + bot 코드 변경 시 bridge 재시작.
#
# spec: docs/features/bridge-auto-deploy.md (#1475)
# 호출: mobruji-bridge-autodeploy.timer 가 5분 간격으로 root 로 실행.
#
# 권한 모델:
#   - git 작업은 checkout 소유자(mobruji)로 위임 (runuser) — root-owned 파일 오염 방지.
#   - systemctl restart 는 root 로 (timer service 가 root 실행).
#
# 안전장치:
#   - fast-forward 가능할 때만 pull. 로컬이 develop 에서 갈라졌으면 손대지 않고 경고 후 exit 1.
#   - bot 이 실제 실행하는 코드(tools/discord-daemon/ · tools/agent/) 변경 시에만 재시작 —
#     무관한 develop 변경(web/backend/docs)으로 인한 불필요한 Discord 브리지 중단 회피.
#   - 재시작 후 active 검증 — 실패 시 exit 1 (journal 에 ERROR 박제).
set -euo pipefail

BRIDGE_DIR=/home/mobruji/mobruji-bridge
SERVICE=mobruji-discord-bridge.service
RUN_AS=mobruji
# bot 이 import/실행하는 코드 경로 — 이 경로 변경 시에만 재시작.
WATCH_RE='^tools/(discord-daemon|agent)/'

g() { runuser -u "$RUN_AS" -- git -C "$BRIDGE_DIR" "$@"; }
log() { echo "[bridge-autodeploy] $*"; }

g fetch --quiet origin develop
local_sha=$(g rev-parse HEAD)
remote_sha=$(g rev-parse origin/develop)

if [ "$local_sha" = "$remote_sha" ]; then
  log "up-to-date (${local_sha:0:8})"
  exit 0
fi

# fast-forward 가능(local 이 remote 의 조상)할 때만 — 로컬 분기 시 clobber 방지.
if ! g merge-base --is-ancestor "$local_sha" "$remote_sha"; then
  log "WARN: ${local_sha:0:8} 가 origin/develop 조상 아님 (로컬 분기) — 수동 확인 필요, skip"
  exit 1
fi

changed=$(g diff --name-only "$local_sha" "$remote_sha")
g pull --ff-only --quiet origin develop
file_count=$(printf '%s\n' "$changed" | grep -c . || true)
log "pulled ${local_sha:0:8} -> ${remote_sha:0:8} (${file_count} files)"

if printf '%s\n' "$changed" | grep -qE "$WATCH_RE"; then
  systemctl restart "$SERVICE"
  sleep 5
  if systemctl is-active --quiet "$SERVICE"; then
    log "restarted $SERVICE (bot 코드 변경 감지) — active"
  else
    log "ERROR: $SERVICE 재시작 후 active 아님"
    exit 1
  fi
else
  log "bot 코드 변경 없음 — checkout 만 갱신, 재시작 생략"
fi
