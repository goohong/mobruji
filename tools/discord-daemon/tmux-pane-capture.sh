#!/usr/bin/env bash
# tmux-pane-capture.sh — tmux pane stdout capture + size 기반 로그 로테이션.
#
# spec: docs/features/discord-driven-mobruji.md §5-2 컴포넌트 3, §6 PR C, §3 F2.
#
# 배경 (#341, discord-driven PR C): maestro tmux pane 의 raw stdout 을 파일로
# 캡처해 디버깅에 쓴다. Phase 1 에선 디버깅 용도만 — 마커 룰 / 자동 회신은
# Phase 2 별도 PR (PR E). 본 스크립트는 캡처 establish + 로테이션만 책임진다.
#
# 비기능 (이슈 #341):
#   - 디스크 사용량 상한 (기본 100MB → roll). KEEP 개 백업 유지 후 GC.
#   - 로그 파일 권한 chmod 600 (메시지 원문 포함 가능 — 8) 보안).
#
# 동작 (subcommand):
#   start   — pane stdout 을 LOG_FILE 로 pipe-pane. 기존 pipe 는 먼저 끈 뒤 재설정
#             (이중 pipe 방지). LOG_FILE 은 touch + chmod 600 후 append.
#   rotate  — LOG_FILE 크기 1회 점검. MAX_BYTES 초과 시 roll (.1 .. .KEEP) + GC +
#             pipe 재설정. 미초과 시 no-op.
#   watch   — INTERVAL 초마다 rotate 를 반복하는 데몬 루프 (LaunchAgent / 별도
#             프로세스에서 구동). pane 부재 시 graceful skip 후 다음 iter 재시도.
#   stop    — pipe-pane off (캡처 중단). LOG_FILE 은 보존.
#
# 동작 보장:
#   - tmux 세션/pane 부재, 권한 없음 모두 graceful — stderr warning + 해당
#     액션 skip. watch 루프는 절대 죽지 않는다 (다음 iter 재시도).
#   - pipe-pane 은 shell 을 통하므로 LOG_FILE 경로는 내부에서만 구성 (사용자
#     입력 미반영) — injection 표면 없음.
#
# 배포:
#   - 워크트리: tools/discord-daemon/tmux-pane-capture.sh (소스 진실)
#   - 운영: ~/.mobruji/tmux-pane-capture.sh (symlink 권장). LaunchAgent 등록은
#     PR D (setup-tmux-bridge.sh) 책임.
#
# 종속: tmux, stat, mv, rm, touch, chmod, sleep.

set -uo pipefail  # -e 의도적 제외 — watch 루프가 한 iter fail 로 죽으면 안 됨.

MOBRUJI_DIR="${MOBRUJI_DIR:-${HOME:-/tmp}/.mobruji}"
TARGET_PANE="${TMUX_TARGET_PANE:-mobruji:0.0}"
LOG_FILE="${TMUX_PANE_LOG:-${MOBRUJI_DIR}/tmux-pane.log}"
MAX_BYTES="${TMUX_PANE_LOG_MAX_BYTES:-104857600}"      # 100MB
KEEP="${TMUX_PANE_LOG_KEEP:-3}"                         # 백업 개수 (.1 .. .KEEP)
INTERVAL="${TMUX_PANE_LOG_CHECK_INTERVAL:-60}"          # watch 점검 주기 (초)

log() { printf '[tmux-pane-capture] %s\n' "$*" >&2; }

# pane 의 session 부분만 추출 (mobruji:0.0 → mobruji) 후 존재 여부 확인.
pane_session_exists() {
  local session="${TARGET_PANE%%:*}"
  tmux has-session -t "$session" >/dev/null 2>&1
}

# LOG_FILE 을 600 권한으로 보장 (없으면 생성). 디렉토리도 보장.
ensure_log_file() {
  mkdir -p "$(dirname "$LOG_FILE")" 2>/dev/null || true
  if [[ ! -e "$LOG_FILE" ]]; then
    touch "$LOG_FILE" 2>/dev/null || {
      log "! LOG_FILE 생성 실패: $LOG_FILE"
      return 1
    }
  fi
  chmod 600 "$LOG_FILE" 2>/dev/null || log "! chmod 600 실패: $LOG_FILE"
  return 0
}

# 현재 pane 의 pipe 를 끈다 (인자 없는 pipe-pane = toggle off if on).
pipe_off() {
  tmux pipe-pane -t "$TARGET_PANE" >/dev/null 2>&1 || true
}

# pane stdout 을 LOG_FILE 로 append. 기존 pipe 는 먼저 off (이중 pipe 방지).
pipe_on() {
  pane_session_exists || {
    log "! pane 부재 — pipe 설정 skip: $TARGET_PANE"
    return 1
  }
  ensure_log_file || return 1
  pipe_off
  # -o: pane 출력만 (입력 echo 제외). cat 으로 raw append.
  if tmux pipe-pane -t "$TARGET_PANE" -o "cat >> '$LOG_FILE'" >/dev/null 2>&1; then
    log "캡처 ON: pane=$TARGET_PANE → $LOG_FILE"
    return 0
  fi
  log "! pipe-pane 설정 실패: pane=$TARGET_PANE"
  return 1
}

# LOG_FILE 바이트 크기 (없으면 0).
file_size() {
  [[ -e "$LOG_FILE" ]] || { echo 0; return; }
  # macOS(stat -f%z) / GNU(stat -c%s) 양쪽 대응.
  stat -f%z "$LOG_FILE" 2>/dev/null || stat -c%s "$LOG_FILE" 2>/dev/null || echo 0
}

# MAX_BYTES 초과 시 roll. .KEEP 가장 오래된 것부터 밀어내고 새 LOG_FILE 시작.
# roll 직전 pipe off → roll → pipe on 으로 cat 이 잡고 있던 inode 갈아끼움.
rotate() {
  local size
  size=$(file_size)
  if [[ "$size" -lt "$MAX_BYTES" ]]; then
    return 0
  fi
  log "로테이션: size=${size}B >= MAX=${MAX_BYTES}B"

  pipe_off

  # 가장 오래된 백업 제거 후 .N → .N+1 로 시프트.
  rm -f "${LOG_FILE}.${KEEP}" 2>/dev/null || true
  local i
  for (( i = KEEP - 1; i >= 1; i-- )); do
    if [[ -e "${LOG_FILE}.${i}" ]]; then
      mv -f "${LOG_FILE}.${i}" "${LOG_FILE}.$(( i + 1 ))" 2>/dev/null || true
    fi
  done
  if [[ -e "$LOG_FILE" ]]; then
    mv -f "$LOG_FILE" "${LOG_FILE}.1" 2>/dev/null || true
    chmod 600 "${LOG_FILE}.1" 2>/dev/null || true
  fi

  ensure_log_file
  # pane 살아있을 때만 캡처 재개 (없으면 다음 watch iter 에서 재시도).
  if pane_session_exists; then
    pipe_on
  else
    log "! roll 후 pane 부재 — 다음 iter 재시도"
  fi
}

# INTERVAL 마다 rotate. pane 죽었다 살아나면 캡처 자동 재개.
watch() {
  log "watch 시작: pane=$TARGET_PANE interval=${INTERVAL}s max=${MAX_BYTES}B keep=${KEEP}"
  # 부팅 시 1회 캡처 establish 시도 (pane 있으면).
  pipe_on || true
  while true; do
    sleep "$INTERVAL"
    if pane_session_exists; then
      rotate
    fi
  done
}

usage() {
  cat >&2 <<EOF
사용: $(basename "$0") <start|rotate|watch|stop>
  start   pane stdout → LOG_FILE 캡처 establish (1회)
  rotate  size 점검 후 필요 시 roll (1회)
  watch   INTERVAL 마다 rotate 반복 (데몬)
  stop    캡처 중단 (LOG_FILE 보존)

환경변수 (기본값):
  MOBRUJI_DIR=${MOBRUJI_DIR}
  TMUX_TARGET_PANE=${TARGET_PANE}
  TMUX_PANE_LOG=${LOG_FILE}
  TMUX_PANE_LOG_MAX_BYTES=${MAX_BYTES}
  TMUX_PANE_LOG_KEEP=${KEEP}
  TMUX_PANE_LOG_CHECK_INTERVAL=${INTERVAL}
EOF
}

case "${1:-}" in
  start)  pipe_on ;;
  rotate) rotate ;;
  watch)  watch ;;
  stop)   pipe_off; log "캡처 OFF: pane=$TARGET_PANE" ;;
  *)      usage; exit 2 ;;
esac
