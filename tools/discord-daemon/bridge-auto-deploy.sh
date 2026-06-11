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
# 가시화 (#1495):
#   - develop 갈라짐(ff-only 가드)으로 skip 할 때 WARN journal 뿐 아니라
#     `~/.mobruji/autodeploy-status.json` 에 서비스별 diverged 상태를 기록한다.
#     bot.py digest_loop 가 read 해서 cron digest 채널에 노출 → 사용자 즉시 가시.
#   - up-to-date / ff pull 성공 시 해당 서비스 항목을 제거 (회복 반영).
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

# digest 가시화 상태 파일 — RUN_AS(mobruji) 소유로 갱신 (root-owned 오염 방지).
STATUS_FILE="/home/${RUN_AS}/.mobruji/autodeploy-status.json"
SERVICE_KEY="${SERVICE%.service}"

# 서비스별 diverged 항목 기록/제거. python3 로 atomic merge (jq 의존 회피).
update_status() {
  local state="$1" local_short="$2" remote_short="$3"
  runuser -u "$RUN_AS" -- python3 - "$STATUS_FILE" "$SERVICE_KEY" "$state" \
    "$local_short" "$remote_short" <<'PY' || log "WARN: autodeploy-status.json 갱신 실패 (digest 가시화 skip)"
import json, os, sys, datetime, tempfile

path, service, state, local_short, remote_short = sys.argv[1:6]
os.makedirs(os.path.dirname(path), exist_ok=True)
try:
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict):
        data = {}
except (FileNotFoundError, json.JSONDecodeError, OSError):
    data = {}

if state == "ok":
    data.pop(service, None)
else:
    data[service] = {
        "state": state,
        "local": local_short,
        "remote": remote_short,
        "ts": datetime.datetime.now(
            datetime.timezone(datetime.timedelta(hours=9))
        ).isoformat(timespec="seconds"),
    }

fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), suffix=".tmp")
with os.fdopen(fd, "w", encoding="utf-8") as handle:
    json.dump(data, handle, ensure_ascii=False, indent=2)
os.replace(tmp, path)
PY
}

g fetch --quiet origin develop
local_sha=$(g rev-parse HEAD)
remote_sha=$(g rev-parse origin/develop)

if [ "$local_sha" = "$remote_sha" ]; then
  log "up-to-date (${local_sha:0:8})"
  update_status ok "${local_sha:0:8}" "${remote_sha:0:8}"
  exit 0
fi

# fast-forward 가능(local 이 remote 의 조상)할 때만 — 로컬 분기 시 clobber 방지.
if ! g merge-base --is-ancestor "$local_sha" "$remote_sha"; then
  log "WARN: ${local_sha:0:8} 가 origin/develop 조상 아님 (로컬 분기) — 수동 확인 필요, skip"
  update_status diverged "${local_sha:0:8}" "${remote_sha:0:8}"
  exit 1
fi

changed=$(g diff --name-only "$local_sha" "$remote_sha")
g pull --ff-only --quiet origin develop
file_count=$(printf '%s\n' "$changed" | grep -c . || true)
log "pulled ${local_sha:0:8} -> ${remote_sha:0:8} (${file_count} files)"
update_status ok "${local_sha:0:8}" "${remote_sha:0:8}"

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
