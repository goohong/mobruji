#!/usr/bin/env bash
# macOS LaunchAgent 셋업 스크립트 (Discord daemon 옵션 A).
#
# - venv 생성 + 의존성 설치
# - .env 가 없으면 .env.example 로부터 생성 (값은 사용자가 직접 채움)
# - plist 토큰 치환 후 ~/Library/LaunchAgents/ 에 복사
# - launchctl bootstrap 으로 등록 (재실행 시 자동 reload)
#
# 사용: bash tools/discord-daemon/setup-launchagent.sh
set -euo pipefail

DAEMON_DIR="$(cd "$(dirname "$0")" && pwd)"
PLIST_NAME="com.mobruji.discord-daemon.plist"
PLIST_TEMPLATE="${DAEMON_DIR}/${PLIST_NAME}"
PLIST_DEST="${HOME}/Library/LaunchAgents/${PLIST_NAME}"
LOG_DIR="${HOME}/Library/Logs"
VENV_DIR="${DAEMON_DIR}/venv"
PYTHON_BIN="${VENV_DIR}/bin/python"

require_macos() {
  if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "[error] 이 스크립트는 macOS 전용이다. uname=$(uname -s)" >&2
    exit 1
  fi
}

ensure_venv() {
  if [[ ! -x "${PYTHON_BIN}" ]]; then
    echo "[info] venv 생성: ${VENV_DIR}"
    python3 -m venv "${VENV_DIR}"
  else
    echo "[info] venv 이미 있음: ${VENV_DIR}"
  fi
  echo "[info] 의존성 설치"
  "${VENV_DIR}/bin/pip" install --upgrade pip >/dev/null
  "${VENV_DIR}/bin/pip" install -r "${DAEMON_DIR}/requirements.txt"
}

ensure_env_file() {
  local env_path="${DAEMON_DIR}/.env"
  if [[ -f "${env_path}" ]]; then
    echo "[info] .env 이미 있음 (skip)"
    return
  fi
  echo "[info] .env 생성 (.env.example 복사). 값을 직접 채워라."
  cp "${DAEMON_DIR}/.env.example" "${env_path}"
  chmod 600 "${env_path}"
}

render_plist() {
  echo "[info] plist 렌더링 → ${PLIST_DEST}"
  mkdir -p "${LOG_DIR}"
  mkdir -p "$(dirname "${PLIST_DEST}")"
  sed \
    -e "s|{{DAEMON_DIR}}|${DAEMON_DIR}|g" \
    -e "s|{{PYTHON_BIN}}|${PYTHON_BIN}|g" \
    -e "s|{{LOG_DIR}}|${LOG_DIR}|g" \
    "${PLIST_TEMPLATE}" > "${PLIST_DEST}"
}

reload_agent() {
  local target="gui/$(id -u)"
  if launchctl print "${target}/com.mobruji.discord-daemon" >/dev/null 2>&1; then
    echo "[info] 기존 LaunchAgent unload"
    launchctl bootout "${target}" "${PLIST_DEST}" || true
  fi
  echo "[info] LaunchAgent bootstrap"
  launchctl bootstrap "${target}" "${PLIST_DEST}"
  launchctl enable "${target}/com.mobruji.discord-daemon" || true
  launchctl kickstart -k "${target}/com.mobruji.discord-daemon"
}

print_next_steps() {
  cat <<EOF

[done] LaunchAgent 등록 완료.

다음 단계:
  1. ${DAEMON_DIR}/.env 의 DISCORD_BOT_TOKEN / GITHUB_PAT 를 채운다.
  2. 값 변경 후 다시 적용: bash ${DAEMON_DIR}/setup-launchagent.sh
  3. 로그 tail: tail -F ${LOG_DIR}/mobruji-discord-daemon.{out,err}.log
  4. (선택) 절전 OFF — Mac이 sleep 들면 데몬도 멈춘다:
       sudo pmset -a disablesleep 1     # 시스템 sleep 차단 (디스플레이는 꺼짐)
       또는: caffeinate -dimsu &        # 임시
  5. 중지: launchctl bootout gui/\$(id -u) ${PLIST_DEST}

EOF
}

main() {
  require_macos
  ensure_venv
  ensure_env_file
  render_plist
  reload_agent
  print_next_steps
}

main "$@"
