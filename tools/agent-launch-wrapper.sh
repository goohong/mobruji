#!/usr/bin/env bash
# tools/agent-launch-wrapper.sh — sub-agent launch 직전 cycle-status set-active 자동화 (#1008).
#
# 배경:
#   nmae 가 매 sub-agent launch 전 `tools/cycle-status/update.sh <ws> set-active` 를
#   호출하는 룰 ([[feedback-cycle-status-json]]) 이 학습 의존 → 종종 누락.
#   이 wrapper 가 set-active 호출 + 표준 출력으로 launch 안내 prompt 를 emit.
#
# 사용법:
#   tools/agent-launch-wrapper.sh <worktree> --title "..." [--task "..."] \
#       [--echo-prompt "Agent 도구 launch 시 사용할 prompt 본문"]
#
# 흐름:
#   1) `tools/cycle-status/update.sh <worktree> set-active --title "..." [--task "..."]`
#      호출. exit code 비제로면 wrapper 즉시 fail (Agent launch 차단).
#   2) `--echo-prompt` 가 주어졌으면 그 내용을 stdout 으로 출력 (nmae 가 Agent 도구
#      input 에 그대로 붙여넣을 수 있게).
#   3) 부재 시 짧은 confirm 한 줄만 출력.
#
# 환경:
#   CYCLE_STATUS_PATH (default: ~/.mobruji/cycle-status.json) — update.sh 가 사용.
#
# 의도:
#   - nmae 학습 부담 ↓ (한 명령으로 set-active + launch 안내 묶음).
#   - update.sh 호출 실수 / 누락 자동 차단 (wrapper 실패 시 launch 안 함).
#
# 검증:
#   tools/tests/test_agent_launch_wrapper.sh
#
# 관련:
#   - spec: docs/features/autonomous-cycle-orchestration.md
#   - 메모리: [[feedback-cycle-status-json]] [[feedback-keep-4-cycles-active]]

set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
usage: agent-launch-wrapper.sh <worktree> --title "..." [--task "..."] [--echo-prompt "..."]

  <worktree>        be | fe | rev | plan
  --title TEXT      cycle-status.json in_progress.title 로 기록 (필수).
  --task TEXT       선택. 사용자 친화 task 한 줄.
  --echo-prompt     선택. 본문을 stdout 으로 emit (nmae 가 Agent 도구 prompt 로 사용).

환경:
  CYCLE_STATUS_PATH   (default: ~/.mobruji/cycle-status.json)
USAGE
  exit 2
}

if [[ $# -lt 1 ]]; then
  usage
fi

WORKTREE="$1"
shift

case "$WORKTREE" in
  be|fe|rev|plan) ;;
  *)
    echo "ERROR: worktree must be one of be/fe/rev/plan (got: $WORKTREE)" >&2
    exit 2
    ;;
esac

TITLE=""
TASK=""
ECHO_PROMPT=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --title)
      shift; [[ $# -gt 0 ]] || { echo "ERROR: --title requires value" >&2; exit 2; }
      TITLE="$1"
      ;;
    --task)
      shift; [[ $# -gt 0 ]] || { echo "ERROR: --task requires value" >&2; exit 2; }
      TASK="$1"
      ;;
    --echo-prompt)
      shift; [[ $# -gt 0 ]] || { echo "ERROR: --echo-prompt requires value" >&2; exit 2; }
      ECHO_PROMPT="$1"
      ;;
    -h|--help)
      usage
      ;;
    *)
      echo "ERROR: unknown arg: $1" >&2
      usage
      ;;
  esac
  shift
done

if [[ -z "$TITLE" ]]; then
  echo "ERROR: --title 필수" >&2
  exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
UPDATE_SH="$SCRIPT_DIR/cycle-status/update.sh"

if [[ ! -x "$UPDATE_SH" ]]; then
  # update.sh 가 실행 권한 없거나 부재 — 환경 문제. wrapper fail (silent skip 금지).
  echo "ERROR: $UPDATE_SH 실행 불가 (없거나 권한 없음)" >&2
  exit 3
fi

UPDATE_ARGS=("$WORKTREE" "set-active" "--title" "$TITLE")
if [[ -n "$TASK" ]]; then
  UPDATE_ARGS+=("--task" "$TASK")
fi

# update.sh 실패 시 wrapper 도 즉시 fail — Agent launch 차단 (cycle-status drift 방어).
if ! "$UPDATE_SH" "${UPDATE_ARGS[@]}" >&2; then
  echo "ERROR: cycle-status update.sh 호출 실패 — Agent launch 차단" >&2
  exit 4
fi

if [[ -n "$ECHO_PROMPT" ]]; then
  # nmae 가 Agent 도구 prompt 로 그대로 사용. trailing newline 한 줄만 보장.
  printf '%s\n' "$ECHO_PROMPT"
else
  printf 'cycle-status set-active OK (worktree=%s, title=%s) — Agent 도구 launch 진행하세요.\n' \
    "$WORKTREE" "$TITLE"
fi
