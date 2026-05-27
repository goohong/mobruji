#!/usr/bin/env bash
# helper-turn-start.sh — helper 에이전트 매 turn 필수 정보 요약 (Messenger-First)
#
# 이 스크립트는 Helper가 "생각"하거나 "조사"하는 시간을 최소화하기 위해
# nmae(Maestro)의 상태와 프로젝트 현황을 한눈에 볼 수 있게 정제하여 제공합니다.

set -uo pipefail

MOBRUJI_DIR="${MOBRUJI_DIR:-${HOME:-/tmp}/.mobruji}"
CYCLE_STATUS_FILE="${MOBRUJI_DIR}/cycle-status.json"
NMAE_PANE="mobruji:0.0"

echo "=== HELPER TURN-START (MESSENGER-FIRST) ==="

# 1. nmae(Maestro) 현재 화면 요약
echo "[1/3] nmae STATUS (Current Pane View):"
if command -v tmux >/dev/null 2>&1; then
    # 마지막 5줄만 캡처하여 nmae가 무엇을 하고 있는지 확인
    tmux capture-pane -t "$NMAE_PANE" -p | sed '/^$/d' | tail -n 5 || echo "(nmae pane capture 실패)"
else
    echo "(tmux command not found)"
fi

# 2. 프로젝트 및 사이클 요약
echo -e "\n[2/3] PROJECT & CYCLE SUMMARY:"
if [[ -r "$CYCLE_STATUS_FILE" ]] && command -v jq >/dev/null 2>&1; then
    jq -r 'to_entries | map("\(.key): \(.value.in_progress.title // "idle")") | join(" | ")' "$CYCLE_STATUS_FILE" 2>/dev/null || echo "(cycle-status parse 실패)"
else
    echo "(cycle-status.json 부재 또는 jq 없음)"
fi

# 3. 최근 활동 (Git/GH)
echo -e "\n[3/3] RECENT ACTIVITY (Read-only context):"
git log --oneline -n 3 2>/dev/null || echo "(git log 실패)"
if command -v gh >/dev/null 2>&1; then
    gh pr list --state open --limit 3 --json number,title | jq -r '.[] | "#\(.number): \(.title)"' 2>/dev/null || echo "(gh pr list 실패)"
fi

echo -e "\n=== HELPER MUST: (1) Ack (2) Report using info above (3) Delegate ONLY if needed ==="
