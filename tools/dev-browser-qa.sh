#!/usr/bin/env bash
# tools/dev-browser-qa.sh — rev / nmae / sub-agent 가 브라우저로 직접 QA 가능한
# 영구 환경 wrapper. chrome headless + dev server + CDP (remote-debugging) 셋업.
#
# 사용자 명시 directive 2026-05-31 "브라우저로 직접 테스트하는 환경 구축하자".
# 본 nmae 가 직접 chrome 148 install + dev server + CDP 진행한 path 를 wrapper 로
# 영구 박제 — 다음 사이클부터 sub-agent 가 1줄 호출로 동일 환경 진입.
#
# 사용법:
#   tools/dev-browser-qa.sh start [<dev-port=4322>] [<cdp-port=9222>]
#   tools/dev-browser-qa.sh stop
#   tools/dev-browser-qa.sh status
#   tools/dev-browser-qa.sh test-darkmode <route=/>      # 단일 route 다크 / 라이트 비교
#   tools/dev-browser-qa.sh test-darkmode-all            # 9 페이지 일괄 검증 + matrix 출력
#
# 의존:
#   - google-chrome (apt install 또는 .deb)
#   - next.js dev server (web/ 안 npm next dev --webpack)
#   - python3 (stdlib only — CDP WebSocket client)
#
# 환경변수:
#   DEV_PORT       (default 4322)
#   CDP_PORT       (default 9222)
#   WEB_DIR        (default 자동 탐지 — /home/mobruji/mobruji-{be,fe,rev,plan,bridge}/web 중 첫 매치)
#   CHROME_BIN     (default google-chrome)
#   QA_OUT_DIR     (default /tmp/dev-browser-qa)
#
# spec: docs/features/dev-browser-qa-env.md

set -euo pipefail

DEV_PORT="${DEV_PORT:-4322}"
CDP_PORT="${CDP_PORT:-9222}"
CHROME_BIN="${CHROME_BIN:-google-chrome}"
QA_OUT_DIR="${QA_OUT_DIR:-/tmp/dev-browser-qa}"
CHROME_PROFILE_DIR="${CHROME_PROFILE_DIR:-/tmp/dev-browser-qa-profile}"
DEV_LOG="${QA_OUT_DIR}/dev-server.log"
CHROME_LOG="${QA_OUT_DIR}/chrome.log"

mkdir -p "$QA_OUT_DIR"

detect_web_dir() {
  if [[ -n "${WEB_DIR:-}" ]]; then echo "$WEB_DIR"; return; fi
  for d in /home/mobruji/mobruji-rev /home/mobruji/mobruji-be /home/mobruji/mobruji-fe /home/mobruji/mobruji-plan /home/mobruji/mobruji-bridge; do
    if [[ -d "$d/web" && -e "$d/web/node_modules" ]]; then
      echo "$d/web"; return
    fi
  done
  echo "ERROR: web/ + node_modules 가진 워크트리 미발견" >&2
  exit 2
}

is_dev_running() {
  curl -s -o /dev/null -w "%{http_code}" --max-time 2 "http://localhost:${DEV_PORT}/" 2>/dev/null | grep -q "200\|3"
}

is_chrome_running() {
  curl -s -o /dev/null -w "%{http_code}" --max-time 2 "http://localhost:${CDP_PORT}/json/version" 2>/dev/null | grep -q "200"
}

cmd_start() {
  local web_dir; web_dir="$(detect_web_dir)"
  echo "WEB_DIR=$web_dir DEV_PORT=$DEV_PORT CDP_PORT=$CDP_PORT"

  if is_dev_running; then
    echo "dev server: already running (port $DEV_PORT)"
  else
    local next_bin
    next_bin="$(readlink -f "$web_dir/node_modules/.bin/next" 2>/dev/null || echo "")"
    [[ -z "$next_bin" || ! -x "$next_bin" ]] && next_bin="/data/node_modules/.bin/next"
    [[ ! -x "$next_bin" ]] && { echo "ERROR: next CLI 미발견 ($next_bin)"; exit 2; }

    echo "dev server start: $next_bin dev --webpack -p $DEV_PORT (log=$DEV_LOG)"
    (cd "$web_dir" && nohup "$next_bin" dev --webpack -p "$DEV_PORT" > "$DEV_LOG" 2>&1 &)
    for i in {1..30}; do
      sleep 1
      if is_dev_running; then echo "dev server ready ($((i))s)"; break; fi
      [[ $i -eq 30 ]] && { echo "ERROR: dev server 30s timeout — log:"; tail -10 "$DEV_LOG"; exit 2; }
    done
  fi

  if is_chrome_running; then
    echo "chrome: already running (CDP port $CDP_PORT)"
  else
    command -v "$CHROME_BIN" >/dev/null || { echo "ERROR: $CHROME_BIN 미설치 — 'sudo apt install -y ./google-chrome-stable_current_amd64.deb'"; exit 2; }
    rm -rf "$CHROME_PROFILE_DIR"
    echo "chrome start: CDP port $CDP_PORT (log=$CHROME_LOG)"
    nohup "$CHROME_BIN" --headless=new --no-sandbox --disable-gpu --disable-software-rasterizer \
      --no-first-run --disable-extensions --disable-dev-shm-usage \
      --remote-debugging-port="$CDP_PORT" --remote-allow-origins='*' \
      --user-data-dir="$CHROME_PROFILE_DIR" --window-size=1280,800 --hide-scrollbars \
      'about:blank' > "$CHROME_LOG" 2>&1 &
    for i in {1..15}; do
      sleep 1
      if is_chrome_running; then echo "chrome CDP ready ($((i))s)"; break; fi
      [[ $i -eq 15 ]] && { echo "ERROR: chrome CDP 15s timeout — log:"; tail -10 "$CHROME_LOG"; exit 2; }
    done
  fi

  echo
  echo "✅ ready — dev: http://localhost:$DEV_PORT/  cdp: http://localhost:$CDP_PORT/json/version"
}

cmd_stop() {
  pkill -f "next dev .*$DEV_PORT" 2>/dev/null && echo "dev server killed" || echo "dev server already stopped"
  pkill -f "$CHROME_BIN.*remote-debugging-port=$CDP_PORT" 2>/dev/null && echo "chrome killed" || echo "chrome already stopped"
}

cmd_status() {
  is_dev_running && echo "✅ dev server alive (port $DEV_PORT)" || echo "❌ dev server stopped"
  is_chrome_running && echo "✅ chrome CDP alive (port $CDP_PORT)" || echo "❌ chrome stopped"
}

cmd_test_darkmode() {
  local route="${1:-/}"
  is_dev_running || { echo "ERROR: dev server not running — 'start' 먼저"; exit 2; }
  is_chrome_running || { echo "ERROR: chrome not running — 'start' 먼저"; exit 2; }

  local out_prefix="$QA_OUT_DIR/darkmode_$(echo "$route" | tr '/' '_')"
  python3 "$(dirname "$0")/dev_browser_qa.py" \
    --cdp-port "$CDP_PORT" --dev-port "$DEV_PORT" --route "$route" --out-prefix "$out_prefix"
}

DARKMODE_ALL_ROUTES=(
  "/" "/voice-range" "/recommend" "/songs"
  "/history" "/likes" "/bookmarks" "/offline" "/maintenance"
)

cmd_test_darkmode_all() {
  is_dev_running || { echo "ERROR: dev server not running — 'start' 먼저"; exit 2; }
  is_chrome_running || { echo "ERROR: chrome not running — 'start' 먼저"; exit 2; }

  echo "=== darkmode QA matrix (${#DARKMODE_ALL_ROUTES[@]} routes) ==="
  echo
  local pass=0 fail=0 results=()
  : > "$QA_OUT_DIR/test-all.log"
  for route in "${DARKMODE_ALL_ROUTES[@]}"; do
    local out_prefix="$QA_OUT_DIR/darkmode_all_$(echo "$route" | tr '/' '_')"
    local rc=0
    local last_line
    last_line="$(python3 "$(dirname "$0")/dev_browser_qa.py" \
      --cdp-port "$CDP_PORT" --dev-port "$DEV_PORT" \
      --route "$route" --out-prefix "$out_prefix" 2>&1 | tee -a "$QA_OUT_DIR/test-all.log" | tail -1)" || rc=$?
    if [[ $rc -eq 0 ]]; then
      pass=$((pass+1)); results+=("✅ $route — $last_line")
    else
      fail=$((fail+1)); results+=("❌ $route — $last_line")
    fi
  done

  echo
  echo "=== matrix (pass=$pass / fail=$fail / total=${#DARKMODE_ALL_ROUTES[@]}) ==="
  printf '%s\n' "${results[@]}"
  echo
  echo "evidence: $QA_OUT_DIR/darkmode_all_*.png + $QA_OUT_DIR/test-all.log"
  [[ $fail -eq 0 ]] || exit 1
}

case "${1:-}" in
  start) shift; cmd_start "$@" ;;
  stop)  cmd_stop ;;
  status) cmd_status ;;
  test-darkmode) shift; cmd_test_darkmode "$@" ;;
  test-darkmode-all) shift; cmd_test_darkmode_all ;;
  ""|help|-h|--help)
    sed -n '/^# 사용법/,/^# 의존:/p' "$0" | sed 's/^# \?//'
    ;;
  *) echo "unknown cmd: $1"; exit 2 ;;
esac
