#!/usr/bin/env bash
# mobruji 체크아웃 자동 배포 — develop 최신 동기 + 감시 경로 코드 변경 시 서비스 재시작.
#
# spec: docs/features/bridge-auto-deploy.md (#1475 bridge, #1479 agent 일반화)
# 호출: mobruji-{bridge,agent}-autodeploy.timer 가 5분 간격으로 root 로 실행.
#
# 사용법: bridge-auto-deploy.sh [CHECKOUT_DIR] [SERVICE] [WATCH_RE] [RUN_AS]
#   인자 없으면 bridge 기본값 — 기존 mobruji-bridge-autodeploy.service 가 인자 없이
#   호출하던 동작을 그대로 보존(하위 호환). agent 등 다른 체크아웃은 인자로 지정한다.
#   - CHECKOUT_DIR: git 체크아웃 경로 (develop 고정). 기본 /home/mobruji/mobruji-bridge
#   - SERVICE: 재시작할 systemd 서비스. 기본 mobruji-discord-bridge.service
#   - WATCH_RE: 이 정규식에 맞는 파일 변경 시에만 재시작. 기본 bridge 코드 경로.
#   - RUN_AS: git 작업 위임 사용자. 기본 mobruji.
#
# 권한 모델:
#   - git 작업은 checkout 소유자(mobruji)로 위임 (runuser) — root-owned 파일 오염 방지.
#   - systemctl restart 는 root 로 (timer service 가 root 실행).
#
# 안전장치:
#   - fast-forward 가능할 때만 pull. 로컬이 develop 에서 갈라졌으면 손대지 않고 경고 후 exit 1.
#   - 서비스가 실제 실행하는 코드(WATCH_RE) 변경 시에만 재시작 — 무관한 develop 변경으로 인한
#     불필요한 서비스 중단 회피.
#   - 재시작 후 active 검증 — 실패 시 exit 1 (journal 에 ERROR 기록).
#
# 참고: venv 의존성(pip)은 갱신하지 않는다 — 코드 pull 만. 신규 의존성 추가 PR 은 수동 설치 필요.
set -euo pipefail

CHECKOUT_DIR=${1:-/home/mobruji/mobruji-bridge}
SERVICE=${2:-mobruji-discord-bridge.service}
# bot 이 import/실행하는 코드 경로 — 이 경로 변경 시에만 재시작.
WATCH_RE=${3:-'^tools/(discord-daemon|agent)/'}
RUN_AS=${4:-mobruji}

g() { runuser -u "$RUN_AS" -- git -C "$CHECKOUT_DIR" "$@"; }
log() { echo "[autodeploy:${SERVICE%.service}] $*"; }

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
    log "restarted $SERVICE (감시 경로 변경 감지) — active"
  else
    log "ERROR: $SERVICE 재시작 후 active 아님"
    exit 1
  fi
else
  log "감시 경로 변경 없음 — checkout 만 갱신, 재시작 생략"
fi
