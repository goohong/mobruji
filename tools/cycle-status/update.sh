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
# set-idle(rev) 시 완료 retag 후속 처리를 위해 exec 대신 일반 호출 후 rc 보존.
RC=0
python3 "$(dirname "$0")/update.py" \
  --path "$CYCLE_STATUS_PATH" \
  --worktree "$WORKTREE" \
  --action "$ACTION" \
  "$@" || RC=$?

# rev 사이클 완료(set-idle) 시 launch thread 를 ✅ 완료로 retag.
# 사이클 forum 태그 ✅ 완료 전이는 본래 머지 PR 본문 cycle-forum 교차참조에만
# 의존(bot.py cycle_thread_complete_on_merge_loop)했는데, rev 는 자체 PR 을
# 머지하지 않아 launch thread 가 ⏳ 진행에 영구 고정됐다. set-idle 이 rev 의
# 실제 완료 신호이므로 여기서 보강한다 (cycle-forum-operation.md §5-8).
# graceful — retag 실패가 set-idle 자체를 막지 않는다 (cycle-status 는 이미 갱신됨).
if [[ "$RC" -eq 0 && "$ACTION" == "set-idle" && "$WORKTREE" == "rev" ]]; then
  ACTIVE_THREAD_FILE="${HOME:-/tmp}/.mobruji/cycle-active-thread/${WORKTREE}.txt"
  if [[ -r "$ACTIVE_THREAD_FILE" ]]; then
    ACTIVE_THREAD_ID=$(head -1 "$ACTIVE_THREAD_FILE" 2>/dev/null | tr -d '[:space:]')
    if [[ "$ACTIVE_THREAD_ID" =~ ^[0-9]{17,20}$ ]]; then
      DISCORD_REPLY_SH="${DISCORD_REPLY_SH:-${HOME:-/tmp}/.mobruji/discord-reply.sh}"
      if [[ ! -x "$DISCORD_REPLY_SH" ]]; then
        ALT="$(dirname "$0")/../discord-daemon/discord-reply.sh"
        if [[ -x "$ALT" ]]; then
          DISCORD_REPLY_SH="$ALT"
        fi
      fi
      if [[ -x "$DISCORD_REPLY_SH" ]]; then
        if "$DISCORD_REPLY_SH" --forum-retag "$ACTIVE_THREAD_ID" "$WORKTREE" "완료" \
            >/dev/null 2>&1; then
          rm -f "$ACTIVE_THREAD_FILE" 2>/dev/null || true
          echo "update.sh: rev launch thread ✅ 완료 retag OK (thread=$ACTIVE_THREAD_ID)" >&2
        else
          echo "update.sh: rev launch thread 완료 retag 실패 (thread=$ACTIVE_THREAD_ID) — graceful" >&2
        fi
      else
        echo "update.sh: discord-reply.sh 부재 — rev 완료 retag skip (thread=$ACTIVE_THREAD_ID)" >&2
      fi
    fi
  fi
fi

exit "$RC"
