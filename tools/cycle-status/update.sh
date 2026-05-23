#!/usr/bin/env bash
# tools/cycle-status/update.sh — ~/.mobruji/cycle-status.json 갱신 헬퍼.
#
# nmae 가 sub-agent launch / 완료 / idle 진입 시 호출. python3 + jq 없이
# 순수 python3 (표준 라이브러리) 로 atomic write (temp + rename).
#
# 사용법:
#   update.sh <worktree> set-active   --title "..." [--task "..."]
#   update.sh <worktree> set-idle     --note "..."
#   update.sh <worktree> set-completed --pr "#NNN" --title "..."
#
# 환경:
#   CYCLE_STATUS_PATH (default: ~/.mobruji/cycle-status.json)
#
# spec: docs/features/nmae-cycle-watchdog.md
set -euo pipefail

CYCLE_STATUS_PATH="${CYCLE_STATUS_PATH:-$HOME/.mobruji/cycle-status.json}"

if [[ $# -lt 2 ]]; then
  echo "usage: update.sh <worktree> <action> [options...]" >&2
  exit 2
fi

WORKTREE="$1"
ACTION="$2"
shift 2

case "$WORKTREE" in
  be|fe|rev|plan) ;;
  *)
    echo "ERROR: worktree must be one of be/fe/rev/plan (got: $WORKTREE)" >&2
    exit 2
    ;;
esac

# Forward to python3 implementation — JSON manipulation 안전성을 위해.
exec python3 "$(dirname "$0")/update.py" \
  --path "$CYCLE_STATUS_PATH" \
  --worktree "$WORKTREE" \
  --action "$ACTION" \
  "$@"
