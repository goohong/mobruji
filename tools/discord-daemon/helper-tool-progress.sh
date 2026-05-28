#!/usr/bin/env bash
# helper-tool-progress.sh — Claude Code PreToolUse hook → Discord thread push.
#
# spec: docs/features/helper-tool-visibility.md (2026-05-29).
#
# 배경:
#   사용자가 helper 가 어떤 도구를 호출하는지 (Bash / Read / Edit / Write 등) 실시간
#   가시화 요청 — "터미널처럼 보이는거지? 뭘 호출하느냐를 넘어서 ~라는 것이군".
#   Claude Code 의 PreToolUse hook 으로 도구 호출 직전 stdin JSON 으로 hook event
#   를 받음 → 본 script 가 parse → discord-reply.sh --auto-thread 로 helper 의
#   현재 thread (helper-current-thread.txt) 에 push.
#
# Range:
#   helper 본체만 — nmae / sub-agent 호출 시 noise 방지. 환경 변수 marker
#   MOBRUJI_HOOK_ACTOR=helper (helper-launch.sh 가 set) 인 경우에만 실행.
#
# 입력 (stdin JSON, Claude Code hook spec):
#   {"hook_event_name": "PreToolUse", "tool_name": "Bash",
#    "tool_input": {"command": "...", ...}, ...}
#
# 출력:
#   - stdout: 빈 줄 (hook 가 stdout 을 컨텍스트에 추가하지 않도록).
#   - exit 0 항상 (hook 실패가 helper 도구 호출 자체 차단 금지 — graceful).
#
# 환경 변수:
#   MOBRUJI_HOOK_ACTOR=helper   — 본 script 실행 조건 (helper-launch.sh 가 export).
#   MOBRUJI_TOOL_PROGRESS=0     — 임시 OFF (개발/디버깅).
#   MOBRUJI_DIR=~/.mobruji      — discord-reply.sh 위치.

set -uo pipefail

# graceful exit — 어떤 단계 실패해도 도구 호출 자체 차단하지 않는다.
exit_graceful() {
  exit 0
}
trap exit_graceful ERR

# 가드: helper 본체가 아니면 skip (sub-agent / nmae noise 차단).
if [[ "${MOBRUJI_HOOK_ACTOR:-}" != "helper" ]]; then
  exit 0
fi
# 임시 OFF 가드.
if [[ "${MOBRUJI_TOOL_PROGRESS:-1}" == "0" ]]; then
  exit 0
fi

MOBRUJI_DIR="${MOBRUJI_DIR:-${HOME:-/tmp}/.mobruji}"
DISCORD_REPLY="${MOBRUJI_DIR}/discord-reply.sh"
if [[ ! -x "$DISCORD_REPLY" ]]; then
  exit 0
fi

# stdin JSON read (max 64KB 안전).
INPUT=$(head -c 65536)
[[ -z "$INPUT" ]] && exit 0

# jq 미설치 시 silent skip (NCP 환경엔 jq 설치돼 있음 — 다른 hook 도 사용).
if ! command -v jq >/dev/null 2>&1; then
  exit 0
fi

TOOL=$(printf '%s' "$INPUT" | jq -r '.tool_name // empty' 2>/dev/null || echo "")
[[ -z "$TOOL" ]] && exit 0

# noise 가 큰 도구 skip — Read 는 매 turn 다수 발생. 사용자가 원하면 env 로 ON.
if [[ "${MOBRUJI_TOOL_PROGRESS_READ:-0}" != "1" ]]; then
  case "$TOOL" in
    Read|Glob|Grep|TaskList|TaskGet) exit 0 ;;
  esac
fi

# tool 별 짧은 description (1-line, max 100자).
case "$TOOL" in
  Bash)
    CMD=$(printf '%s' "$INPUT" | jq -r '.tool_input.command // empty' 2>/dev/null \
          | head -1 | head -c 100)
    MSG="💬 Bash: ${CMD}"
    ;;
  Edit|Write|NotebookEdit)
    FILE=$(printf '%s' "$INPUT" | jq -r '.tool_input.file_path // empty' 2>/dev/null)
    MSG="✏️ ${TOOL}: $(basename "${FILE:-?}")"
    ;;
  WebFetch|WebSearch)
    Q=$(printf '%s' "$INPUT" | jq -r '.tool_input.url // .tool_input.query // empty' 2>/dev/null \
        | head -c 100)
    MSG="🌐 ${TOOL}: ${Q}"
    ;;
  Agent|Task)
    DESC=$(printf '%s' "$INPUT" | jq -r '.tool_input.description // empty' 2>/dev/null \
           | head -c 100)
    MSG="🤖 ${TOOL}: ${DESC}"
    ;;
  *)
    MSG="🛠️ ${TOOL}"
    ;;
esac

# discord-reply.sh --auto-thread = helper-current-thread.txt 자동 chain.
# 실패 silent — graceful (도구 호출 차단 금지).
"$DISCORD_REPLY" --auto-thread "$MSG" >/dev/null 2>&1 || true
exit 0
